package effekt
package llvm

import effekt.machine

object Transformer {

  def transform(program: machine.Program): List[Definition] =
    program match {
      case machine.Program(declarations, statement) =>
        implicit val MC = ModuleContext();
        implicit val FC = FunctionContext();
        implicit val BC = BlockContext();

        // TODO proper initialization of runtime
        emit(Call("env", envType, malloc, List(ConstantInt(1024))));
        emit(Call("sp", spType, malloc, List(ConstantInt(1024))));
        emit(Store(ConstantGlobal(PointerType(NamedType("Sp")), "base"), LocalReference(spType, "sp")));
        pushReturnAddress("topLevel");

        val terminator = transform(statement);

        val definitions = MC.definitions; MC.definitions = null;
        val basicBlocks = FC.basicBlocks; FC.basicBlocks = null;
        val instructions = BC.instructions; BC.instructions = null;

        val entryBlock = BasicBlock("entry", instructions, terminator);
        val entryFunction = Function(VoidType(), "effektMain", List(), entryBlock :: basicBlocks);
        declarations.map(transform) ++ definitions :+ entryFunction
    }

  def transform(declaration: machine.Declaration): Definition =
    declaration match {
      case machine.DefineForeign(returnType, functionName, parameters, body) =>
        VerbatimFunction(transform(returnType), functionName, parameters.map {
          case machine.Variable(name, tpe) => Parameter(transform(tpe), name)
        }, body)
      case machine.Include(content) =>
        Verbatim(content)
    }

  def transform(statement: machine.Statement)(using ModuleContext, FunctionContext, BlockContext): Terminator =
    statement match {
      case machine.Def(machine.Label(name, environment), body, rest) =>

        defineFunction(name, List(Parameter(envType, "env"), Parameter(spType, "sp"))) {
          loadEnvironment(initialEnvironmentPointer, environment);
          transform(body);
        };

        transform(rest)

      case machine.Jump(label) =>

        storeEnvironment(initialEnvironmentPointer, label.environment);

        emit(TailCall(transform(label), List(LocalReference(envType, "env"), getStackPointer())));
        RetVoid()

      case machine.Substitute(bindings, rest) =>

        withBindings(bindings) { () =>
          transform(rest)
        }

      case machine.Let(variable, tag, values, rest) =>

        val obj = produceObject(values);
        val tmpName = freshName("tmp");
        emit(InsertValue(tmpName, ConstantAggregateZero(positiveType), ConstantInt(tag), 0));
        emit(InsertValue(variable.name, LocalReference(positiveType, tmpName), obj, 1));

        transform(rest)

      case machine.Switch(value, clauses) =>

        val tagName = freshName("tag");
        val objName = freshName("obj");
        emit(ExtractValue(tagName, transform(value), 0));
        emit(ExtractValue(objName, transform(value), 1));
        val labels = clauses.map {
          case machine.Clause(parameters, body) =>
            implicit val BC = BlockContext();
            // TODO reset stack pointer to outer one

            consumeObject(LocalReference(envType, objName), parameters);

            val terminator = transform(body);

            val instructions = BC.instructions; BC.instructions = null;

            val label = freshName("l");
            emit(BasicBlock(label, instructions, terminator));
            label
        };
        labels match {
          case Nil =>
            // TODO more informative way to end program. Clean up too?
            RetVoid()
          case label :: labels =>
            Switch(LocalReference(IntegerType64(), tagName), label, labels.zipWithIndex.map { case (l, i) => (i, l) })
        }

      case machine.New(variable, List(clause), rest) =>
        // TODO multiple methods

        val closureEnvironment = machine.freeVariables(clause).toList;

        val clauseName = freshName(variable.name);

        defineFunction(clauseName, List(Parameter(envType, "obj"), Parameter(envType, "env"), Parameter(spType, "sp"))) {
          consumeObject(LocalReference(envType, "obj"), closureEnvironment);
          loadEnvironment(initialEnvironmentPointer, clause.parameters);
          transform(clause.body);
        };

        val obj = produceObject(closureEnvironment);
        val tmpName = freshName("tmp");
        emit(InsertValue(tmpName, ConstantAggregateZero(negativeType), ConstantGlobal(methodType, clauseName), 0));
        emit(InsertValue(variable.name, LocalReference(negativeType, tmpName), obj, 1));

        transform(rest)

      case machine.Invoke(value, 0, values) =>

        storeEnvironment(initialEnvironmentPointer, values);

        val functionName = freshName("fp");
        val objName = freshName("obj");

        emit(ExtractValue(functionName, transform(value), 0));
        emit(ExtractValue(objName, transform(value), 1));
        emit(TailCall(LocalReference(methodType, functionName), List(LocalReference(envType, objName), initialEnvironmentPointer, getStackPointer())));
        RetVoid()

      case machine.PushFrame(frame, rest) =>

        val frameEnvironment = machine.freeVariables(frame).toList;

        val frameName = freshName("k");

        defineFunction(frameName, List(Parameter(envType, "env"), Parameter(spType, "sp"))) {

          popEnvironment(frameEnvironment);
          loadEnvironment(initialEnvironmentPointer, frame.parameters);

          transform(frame.body);
        };

        pushEnvironment(frameEnvironment);
        pushReturnAddress(frameName);

        transform(rest)

      case machine.Return(values) =>

        storeEnvironment(initialEnvironmentPointer, values);

        val returnAddress = popReturnAddress();
        emit(TailCall(LocalReference(returnAddressType, returnAddress), List(initialEnvironmentPointer, getStackPointer())));
        RetVoid()

      case machine.NewStack(variable, frame, rest) =>
        emit(Call(variable.name, transform(variable.tpe), newStack, List()));

        val frameEnvironment = machine.freeVariables(frame).toList;

        val frameName = freshName("k");

        defineFunction(frameName, List(Parameter(envType, "env"), Parameter(spType, "sp"))) {

          popEnvironment(frameEnvironment);
          loadEnvironment(initialEnvironmentPointer, frame.parameters);

          val newStackPointer = LocalReference(spType, freshName("sp"));
          emit(Call(newStackPointer.name, spType, underflowStack, List(getStackPointer())));
          setStackPointer(newStackPointer);

          transform(frame.body);
        };

        val stackPointerPointer = LocalReference(PointerType(spType), freshName("stkspp"));
        val oldStackPointer = LocalReference(spType, freshName("stksp"));
        emit(GetElementPtr(stackPointerPointer.name, LocalReference(PointerType(NamedType("StkVal")), variable.name), List(0, 0)));
        emit(Load(oldStackPointer.name, stackPointerPointer));
        val temporaryStackPointer = pushEnvironmentOnto(oldStackPointer, frameEnvironment);
        val newStackPointer = pushReturnAddressOnto(temporaryStackPointer, frameName);
        emit(Store(stackPointerPointer, newStackPointer));

        transform(rest)

      case machine.PushStack(value, rest) =>
        val newStackPointerName = freshName("sp");
        emit(Call(newStackPointerName, spType, pushStack, List(transform(value), getStackPointer())));
        setStackPointer(LocalReference(spType, newStackPointerName));
        transform(rest)

      case machine.PopStack(variable, rest) =>
        val newStackPointerName = freshName("sp");
        val tmpName = freshName("tmp");
        val tmpReference = LocalReference(StructureType(List(stkType, spType)), tmpName);
        emit(Call(tmpName, StructureType(List(stkType, spType)), popStack, List(getStackPointer())));
        emit(ExtractValue(variable.name, tmpReference, 0));
        emit(ExtractValue(newStackPointerName, tmpReference, 1));
        setStackPointer(LocalReference(spType, newStackPointerName));
        transform(rest)

      case machine.Run(machine.LiteralInt(n), List(), List(machine.Clause(List(machine.Variable(name, machine.Primitive("Int"))), rest))) =>
        emit(Add(name, ConstantInt(n), ConstantInt(0)));
        transform(rest)

      case machine.Run(machine.CallForeign(name), values, List()) =>
        // TODO careful with calling convention?!?
        val functionType = PointerType(FunctionType(VoidType(), values.map { case machine.Variable(_, tpe) => transform(tpe) }));
        emit(Call("_", VoidType(), ConstantGlobal(functionType, name), values.map(transform)));
        RetVoid()

      case machine.Run(machine.CallForeign(name), values, List(machine.Clause(List(machine.Variable(resultName, resultType)), rest))) =>
        // TODO careful with calling convention?!?
        val functionType = PointerType(FunctionType(transform(resultType), values.map { case machine.Variable(_, tpe) => transform(tpe) }));
        emit(Call(resultName, transform(resultType), ConstantGlobal(functionType, name), values.map(transform)));
        transform(rest)
    }

  def transform(label: machine.Label): ConstantGlobal =
    label match {
      case machine.Label(name, _) => ConstantGlobal(PointerType(FunctionType(VoidType(), List(envType, spType))), name)
    }

  def transform(value: machine.Variable)(using FunctionContext): Operand =
    substitute(value) match {
      case machine.Variable(name, tpe) => LocalReference(transform(tpe), name)
    }

  def positiveType = StructureType(List(IntegerType64(), envType));
  // TODO multiple methods (should be pointer to vtable)
  def negativeType = StructureType(List(methodType, envType));
  def methodType = PointerType(FunctionType(VoidType(), List(envType, envType, spType)));
  def returnAddressType = PointerType(FunctionType(VoidType(), List(envType, spType)))
  def envType = NamedType("Env");
  def spType = NamedType("Sp");
  def stkType = NamedType("Stk");

  def transform(tpe: machine.Type): Type = tpe match {
    case machine.Positive(_)      => positiveType
    case machine.Negative(_)      => negativeType
    case machine.Primitive("Int") => NamedType("Int")
    case machine.Primitive("Stk") => stkType
  }

  def environmentSize(environment: machine.Environment): Int =
    environment.map { case machine.Variable(_, typ) => typeSize(typ) }.sum

  def typeSize(tpe: machine.Type): Int =
    tpe match {
      case machine.Positive(_)      => 16
      case machine.Negative(_)      => 16
      case machine.Primitive("Int") => 8
      case machine.Primitive("Stk") => 8
    }

  def defineFunction(name: String, parameters: List[Parameter])(prog: (FunctionContext, BlockContext) ?=> Terminator): ModuleContext ?=> Unit = {
    implicit val FC = FunctionContext();
    implicit val BC = BlockContext();

    val terminator = prog;

    val basicBlocks = FC.basicBlocks; FC.basicBlocks = null;
    val instructions = BC.instructions; BC.instructions = null;

    val entryBlock = BasicBlock("entry", instructions, terminator);
    val function = Function(VoidType(), name, parameters, entryBlock :: basicBlocks);

    emit(function)
  }

  def initialEnvironmentPointer = LocalReference(envType, "env")

  def loadEnvironment(environmentPointer: Operand, environment: machine.Environment)(using ModuleContext, FunctionContext, BlockContext): Unit = {
    if(environment.isEmpty) {
      ()
    } else {
      val typedEnvironmentPointer = castEnvironmentPointer(environmentPointer, environment);
      loadEnvironmentAt(typedEnvironmentPointer, environment);
    }
  }

  def storeEnvironment(environmentPointer: Operand, environment: machine.Environment)(using ModuleContext, FunctionContext, BlockContext): Unit = {
    if(environment.isEmpty) {
      ()
    } else {
      val typedEnvironmentPointer = castEnvironmentPointer(environmentPointer, environment);
      storeEnvironmentAt(typedEnvironmentPointer, environment);
    }
  }

  def produceObject(environment: machine.Environment)(using ModuleContext, FunctionContext, BlockContext): Operand = {
    if(environment.isEmpty) {
      ConstantNull(envType)
    } else {
      val obj = LocalReference(envType, freshName("obj"));
      emit(Call(obj.name, envType, malloc, List(ConstantInt(environmentSize(environment)))));
      storeEnvironment(obj, environment);
      obj
    }
  }

  def consumeObject(obj: Operand, environment: machine.Environment)(using ModuleContext, FunctionContext, BlockContext): Unit = {
    if(environment.isEmpty) {
      ()
    } else {
      loadEnvironment(obj, environment);
      emit(Call("_", VoidType(), free, List(obj)));
    }
  }

  def pushEnvironment(environment: machine.Environment)(using ModuleContext, FunctionContext, BlockContext): Unit = {
    setStackPointer(pushEnvironmentOnto(getStackPointer(), environment))
  }

  def pushEnvironmentOnto(oldStackPointer: Operand, environment: machine.Environment)(using ModuleContext, FunctionContext, BlockContext): Operand = {
    if(environment.isEmpty) {
      oldStackPointer
    } else {
      val oldTypedPointer = castEnvironmentPointer(oldStackPointer, environment);

      storeEnvironmentAt(oldTypedPointer, environment);

      val newTypedPointer = LocalReference(oldTypedPointer.tpe, freshName("sp.t"));
      emit(GetElementPtr(newTypedPointer.name, oldTypedPointer, List(1)));

      val newStackPointer = LocalReference(spType, freshName("sp"));
      emit(BitCast(newStackPointer.name, newTypedPointer, spType));

      newStackPointer
    }
  }

  def popEnvironment(environment: machine.Environment)(using ModuleContext, FunctionContext, BlockContext): Unit = {
    setStackPointer(popEnvironmentFrom(getStackPointer(), environment))
  }

  def popEnvironmentFrom(oldStackPointer: Operand, environment: machine.Environment)(using ModuleContext, FunctionContext, BlockContext): Operand = {
    if(environment.isEmpty) {
      oldStackPointer
    } else {
      val oldTypedPointer = castEnvironmentPointer(oldStackPointer, environment);

      val newTypedPointer = LocalReference(oldTypedPointer.tpe, freshName("sp.t"));
      emit(GetElementPtr(newTypedPointer.name, oldTypedPointer, List(-1)));

      loadEnvironmentAt(newTypedPointer, environment);

      val newStackPointer = LocalReference(spType, freshName("sp"));
      emit(BitCast(newStackPointer.name, newTypedPointer, spType));

      newStackPointer
    }
  }

  def castEnvironmentPointer(oldEnvironmentPointer: Operand, environment: machine.Environment)(using ModuleContext, FunctionContext, BlockContext): LocalReference = {
    val environmentPointerType = PointerType(StructureType(environment.map {
      case machine.Variable(_, typ) => transform(typ)
    }));
    val newEnvironmentPointer = LocalReference(environmentPointerType, freshName("env.t."));
    emit(BitCast(newEnvironmentPointer.name, oldEnvironmentPointer, environmentPointerType));
    newEnvironmentPointer
  }

  def storeEnvironmentAt(pointer: Operand, environment: machine.Environment)(using ModuleContext, FunctionContext, BlockContext): Unit = {
    environment.zipWithIndex.foreach {
      case (value @ machine.Variable(name, tpe), i) =>
        val field = LocalReference(PointerType(transform(tpe)), freshName(name + "p"));
        emit(GetElementPtr(field.name, pointer, List(0, i)));
        emit(Store(field, transform(value)))
    }
  }

  def loadEnvironmentAt(pointer: Operand, environment: machine.Environment)(using ModuleContext, FunctionContext, BlockContext): Unit = {
    environment.zipWithIndex.foreach {
      case (value @ machine.Variable(name, tpe), i) =>
        val field = LocalReference(PointerType(transform(tpe)), freshName(name + "p"));
        emit(GetElementPtr(field.name, pointer, List(0, i)));
        emit(Load(name, field))
    }
  }

  def pushReturnAddress(frameName: String)(using ModuleContext, FunctionContext, BlockContext): Unit = {
    setStackPointer(pushReturnAddressOnto(getStackPointer(), frameName));
  }

  def pushReturnAddressOnto(oldStackPointer: Operand, returnAddressName: String)(using ModuleContext, FunctionContext, BlockContext): Operand = {

    val pointerType = PointerType(returnAddressType);

    val oldTypedPointer = LocalReference(pointerType, freshName("sp.t"));
    emit(BitCast(oldTypedPointer.name, oldStackPointer, pointerType));

    emit(Store(oldTypedPointer, ConstantGlobal(returnAddressType, returnAddressName)));

    val newTypedPointer = LocalReference(pointerType, freshName("sp.t"));
    emit(GetElementPtr(newTypedPointer.name, oldTypedPointer, List(1)));

    val newStackPointer = LocalReference(spType, freshName("sp"));
    emit(BitCast(newStackPointer.name, newTypedPointer, spType));

    newStackPointer
  }

  def popReturnAddress()(using ModuleContext, FunctionContext, BlockContext): String = {
    val returnAddress = freshName("f");
    setStackPointer(popReturnAddressFrom(getStackPointer(), returnAddress));
    returnAddress
  }

  def popReturnAddressFrom(oldStackPointer: Operand, returnAddressName: String)(using ModuleContext, FunctionContext, BlockContext): Operand = {

    val pointerType = PointerType(returnAddressType);

    val oldTypedPointer = LocalReference(pointerType, freshName("sp.t"));
    emit(BitCast(oldTypedPointer.name, oldStackPointer, pointerType));

    val newTypedPointer = LocalReference(pointerType, freshName("sp.t"));
    emit(GetElementPtr(newTypedPointer.name, oldTypedPointer, List(-1)));

    emit(Load(returnAddressName, newTypedPointer));

    val newStackPointer = LocalReference(spType, freshName("sp"));
    emit(BitCast(newStackPointer.name, newTypedPointer, spType));

    newStackPointer
  }

  def malloc = ConstantGlobal(PointerType(FunctionType(PointerType(IntegerType8()), List(IntegerType64()))), "malloc");
  def free = ConstantGlobal(PointerType(FunctionType(VoidType(), List(PointerType(IntegerType8())))), "free");
  def newStack = ConstantGlobal(PointerType(FunctionType(stkType,List())), "newStack");
  def pushStack = ConstantGlobal(PointerType(FunctionType(spType,List(stkType, spType))), "pushStack");
  def popStack = ConstantGlobal(PointerType(FunctionType(StructureType(List(stkType,spType)),List(spType))), "popStack");
  def underflowStack = ConstantGlobal(PointerType(FunctionType(VoidType(),List(spType))), "underflowStack");

}

  /**
   * Extra info in context
   */
  class ModuleContext() {
    var counter = 0;
    var definitions: List[Definition] = List();
  }

  def emit(definition: Definition)(using C: ModuleContext) = {
    C.definitions = C.definitions :+ definition
  }

  def freshName(name: String)(using C: ModuleContext): String = {
    C.counter = C.counter + 1;
    name + "." + C.counter
  }

  class FunctionContext() {
    var substitution: Map[machine.Variable, machine.Variable] = Map();
    var basicBlocks: List[BasicBlock] = List();
  }

  def emit(basicBlock: BasicBlock)(using C: FunctionContext) = {
    C.basicBlocks = C.basicBlocks :+ basicBlock
  }

  def withBindings[R](bindings: List[(machine.Variable, machine.Variable)])(prog: () => R)(using C: FunctionContext): R = {
    val substitution = C.substitution;
    C.substitution = substitution ++ bindings.map { case (variable -> value) => (variable -> substitution.getOrElse(value, value) ) }.toMap;
    val result = prog();
    C.substitution = substitution
    result
  }

  def substitute(value: machine.Variable)(using C: FunctionContext): machine.Variable = {
    C.substitution.toMap.getOrElse(value, value)
  }

  class BlockContext() {
    var stackPointer: Operand = LocalReference(NamedType("Sp"), "sp");
    var instructions: List[Instruction] = List();
  }

  def emit(instruction: Instruction)(using C: BlockContext) = {
    C.instructions = C.instructions :+ instruction
  }

  def getStackPointer()(using C: BlockContext) = {
    C.stackPointer
  }

  def setStackPointer(stackPointer: Operand)(using C: BlockContext) = {
      C.stackPointer = stackPointer;
    }

