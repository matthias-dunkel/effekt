package effekt
package generator
package ml

import effekt.context.Context
import effekt.lifted.*
import effekt.core.Id
import effekt.symbols.{ Module, Symbol, TermSymbol, Wildcard }
import effekt.util.paths.*
import kiama.output.PrettyPrinterTypes.Document

import scala.annotation.tailrec
import scala.language.implicitConversions
import scala.collection.mutable

object Transformer {

  def runMain(main: MLName): ml.Expr = CPS.runMain(main)

  def compilationUnit(mainSymbol: Symbol, core: ModuleDecl)(using C: Context): ml.Toplevel = {
    ml.Toplevel(toML(core)(using TransformerContext(C)), runMain(name(mainSymbol)))
  }

  /**
   * This is used for both: writing the files to and generating the `require` statements.
   */
  def path(m: Module)(using C: Context): String =
    (C.config.outputPath() / m.path.replace('/', '_')).unixPath + ".sml"


  // interfaces are typed structurally: we only generate one datatype for
  // each arity.
  case class CoData(name: MLName, accessors: List[MLName])

  case class TransformerContext(
    // for error reporting
    compilerContext: Context,

    recordCache: mutable.Map[Int, CoData] = mutable.Map.empty,
    accessorCache: mutable.Map[Id, MLName] = mutable.Map.empty
  )
  implicit def useAsContext(C: TransformerContext): Context = C.compilerContext



  def toML(p: Param): MLName = name(p.id)

  def toML(e: Argument)(using TransformerContext): ml.Expr = e match {
    case e: lifted.Expr => toML(e)
    case b: lifted.Block => toML(b)
    case e: lifted.Evidence => toML(e)
  }

  def toML(module: ModuleDecl)(using TransformerContext): List[ml.Binding] = {
    val decls = sortDeclarations(module.decls).flatMap(toML)
    val externs = module.externs.map(toML)
    val rest = sortDefinitions(module.definitions).map(toML)
    decls ++ externs ++ rest
  }

  /**
   * Sorts the definitions topologically. Fails if functions are mutually recursive, since this
   * is not supported by the ml backend, yet.
   *
   * Keep let-definitions in the order they are, since they might have observable side-effects
   */
  def sortDefinitions(defs: List[Definition])(using C: TransformerContext): List[Definition] = {
    def sort(defs: List[Definition], toSort: List[Definition]): List[Definition] = defs match {
      case (d: Definition.Let) :: rest  =>
        val sorted = sortTopologically(toSort, d => freeVariables(d).vars.keySet, d => d.id)
         sorted ++ (d :: sort(rest, Nil))
      case (d: Definition.Def) :: rest => sort(rest, d :: toSort)
      case Nil => sortTopologically(toSort, d => freeVariables(d).vars.keySet, d => d.id)
    }

    sort(defs, Nil)
  }

  def sortDeclarations(defs: List[Declaration])(using C: TransformerContext): List[Declaration] =
    sortTopologically(defs, d => freeTypeVariables(d), d => d.id)

  def sortTopologically[T](defs: List[T], dependencies: T => Set[Id], id: T => Id)(using C: TransformerContext): List[T] = {
    val ids = defs.map(id).toSet
    val fvs = defs.map{ d => id(d) -> dependencies(d).intersect(ids) }.toMap

    @tailrec
    def go(todo: List[T], out: List[T], emitted: Set[Id]): List[T] =
      if (todo.isEmpty) {
        out
      } else {
        val (noDependencies, rest) = todo.partition { d => (fvs(id(d)) -- emitted).isEmpty }
        if (noDependencies.isEmpty) {
          val mutuals = rest.map(id).mkString(", ")
          C.abort(s"Mutual definitions are currently not supported by this backend.\nThe following definitinos could be mutually recursive: ${mutuals} ")
        } else go(rest, noDependencies ++ out, emitted ++ noDependencies.map(id).toSet)
    }

    go(defs, Nil, Set.empty).reverse
  }

  def tpeToML(tpe: BlockType)(using C: TransformerContext): ml.Type = tpe match {
    case BlockType.Function(tparams, eparams, vparams, bparams, ret) if tparams.nonEmpty =>
      C.abort("polymorphic functions not supported")
    case BlockType.Function(Nil, Nil, Nil, Nil, resType) =>
      ml.Type.Fun(List(ml.Type.Unit), tpeToML(resType))
    case BlockType.Function(Nil, Nil, vtpes, Nil, resType) =>
      ml.Type.Fun(vtpes.map(tpeToML), tpeToML(resType))
    case BlockType.Function(tparams, eparams, vparams, bparams, result) =>
      C.abort("higher order functions currently not supported")
    case BlockType.Interface(typeConstructor, args) =>
      ml.Type.TApp(ml.Type.Data(interfaceNameFor(args.size)), args.map(tpeToML))
  }

  def tpeToML(tpe: ValueType)(using TransformerContext): ml.Type = tpe match {
    case lifted.Type.TUnit => ml.Type.Unit
    case lifted.Type.TInt => ml.Type.Integer
    case lifted.Type.TDouble => ml.Type.Real
    case lifted.Type.TBoolean => ml.Type.Bool
    case lifted.Type.TString => ml.Type.String
    case ValueType.Var(id) => ml.Type.Var(name(id))
    case ValueType.Data(id, Nil) => ml.Type.Data(name(id))
    case ValueType.Data(id, args) => ml.Type.TApp(ml.Type.Data(name(id)), args.map(tpeToML))
    case ValueType.Boxed(tpe) => tpeToML(tpe)
  }

  def toML(decl: Declaration)(using C: TransformerContext): List[ml.Binding] = decl match {

    case Declaration.Data(id: symbols.TypeConstructor.Record, tparams, List(ctor)) =>
      defineRecord(name(id), name(id.constructor), ctor.fields.map { f => name(f.id) })

    case Declaration.Data(id, tparams, ctors) =>
      def constructorToML(c: Constructor): (MLName, Option[ml.Type]) = c match {
        case Constructor(id, fields) =>
          val tpeList = fields.map { f => tpeToML(f.tpe) }
          val tpe = typesToTupleIsh(tpeList)
          (name(id), tpe)
      }

      val tvars: List[ml.Type.Var] = tparams.map(p => ml.Type.Var(name(p)))
      List(ml.Binding.DataBind(name(id), tvars, ctors map constructorToML))

    case Declaration.Interface(id, tparams, operations) =>
      defineInterface(id, operations.map { op => op.id })
  }

  def interfaceNameFor(arity: Int): MLName = MLName(s"Object${arity}")

  def defineInterface(typeName: Id, props: List[Id])(using C: TransformerContext): List[ml.Binding] = {
    val arity = props.size

    val interfaceName = interfaceNameFor(arity)
    val accessorNames = props.zipWithIndex.map { case (id, i) =>
      val name = MLName(s"member${i + 1}of${arity}")
      C.accessorCache.update(id, name)
      name
    }

    if C.recordCache.isDefinedAt(arity) then return Nil

    C.recordCache.update(arity, CoData(interfaceName, accessorNames))

    defineRecord(interfaceName, interfaceName, accessorNames)
  }

  def defineRecord(typeName: MLName, constructorName: MLName, fields: List[MLName])(using TransformerContext): List[ml.Binding] = {
    // we introduce one type var for each property, in order to avoid having to translate types
    val tvars: List[ml.Type.Var] = fields.map(_ => ml.Type.Var(freshName("a")))
    val dataDecl = ml.Binding.DataBind(typeName, tvars, List((constructorName, typesToTupleIsh(tvars))))

    val accessors = fields.zipWithIndex.map {
      case (fieldName, i) =>
        val arg = MLName("arg")
        // _, _, _, arg, _
        val patterns = fields.indices.map {
          j => if j == i then ml.Pattern.Named(arg) else ml.Pattern.Wild()
        }.toList

        ml.Binding.FunBind(fieldName,
          List(ml.Param.Patterned(ml.Pattern.Datatype(constructorName, patterns))),
          ml.Expr.Variable(arg))
    }
    dataDecl :: accessors
  }

  def toML(ext: Extern)(using TransformerContext): ml.Binding = ext match {
    case Extern.Def(id, tparams, params, ret, body) =>
      ml.FunBind(name(id), params map { p => ml.Param.Named(name(p.id.name)) }, RawExpr(body))
    case Extern.Include(contents) =>
      RawBind(contents)
  }

  def toMLExpr(stmt: Stmt)(using C: TransformerContext): CPS = stmt match {
    case lifted.Return(e) => CPS.pure(toML(e))

    case lifted.App(lifted.Member(lifted.BlockVar(x, _), symbols.builtins.TState.get, tpe), _, List(ev)) =>
      CPS.pure(ml.Expr.Deref(ml.Variable(name(x))))

    case lifted.App(lifted.Member(lifted.BlockVar(x, _), symbols.builtins.TState.put, tpe), _, List(ev, value)) =>
      CPS.pure(ml.Expr.Assign(ml.Variable(name(x)), toML(value)))

    case lifted.App(b, targs, args) => CPS.inline { k =>
      ml.Expr.Call(toML(b), (args map toML) ++ List(k.reify))
    }

    case lifted.If(cond, thn, els) =>
      CPS.join { k =>
        ml.If(toML(cond), toMLExpr(thn)(k), toMLExpr(els)(k))
      }

    case lifted.Val(id, binding, body) =>
      toMLExpr(binding).flatMap { value =>
        CPS.inline { k =>
          ml.mkLet(List(ml.ValBind(name(id), value)), toMLExpr(body)(k))
        }
      }

    case lifted.Match(scrutinee, clauses, default) => CPS.join { k =>
      def clauseToML(c: (Id, BlockLit)): ml.MatchClause = {
        val (id, b) = c
        val binders = b.params.map(p => ml.Pattern.Named(name(p.id)))
        val pattern = ml.Pattern.Datatype(name(id), binders)
        val body = toMLExpr(b.body)(k)
        ml.MatchClause(pattern, body)
      }

      ml.Match(toML(scrutinee), clauses map clauseToML, default map { d => toMLExpr(d)(k) })
    }

    // TODO maybe don't drop the continuation here? Although, it is dead code.
    case lifted.Hole() => CPS.inline { k => ml.Expr.RawExpr("raise Hole") }

    case lifted.Scope(definitions, body) => CPS.inline { k => ml.mkLet(sortDefinitions(definitions).map(toML), toMLExpr(body)(k)) }

    case lifted.State(id, init, region, ev, body) if region == symbols.builtins.globalRegion =>
      CPS.inline { k =>
        val bind = ml.Binding.ValBind(name(id), ml.Expr.Ref(toML(init)))
        ml.mkLet(List(bind), toMLExpr(body)(k))
      }

    case lifted.State(id, init, region, ev, body) =>
      CPS.inline { k =>
        val bind = ml.Binding.ValBind(name(id), ml.Call(ml.Consts.fresh)(ml.Variable(name(region)), toML(init)))
        ml.mkLet(List(bind), toMLExpr(body)(k))
      }

    case lifted.Try(body, handler) =>
      val args = ml.Consts.lift :: handler.map(toML)
      CPS.inline { k =>
        ml.Call(CPS.reset(ml.Call(toML(body))(args: _*)), List(k.reify))
      }

    // [[ shift(ev, {k} => body) ]] = ev(k1 => k2 => let k ev a = ev (k1 a) in [[ body ]] k2)
    case Shift(ev, Block.BlockLit(tparams, List(kparam), body)) =>
      CPS.lift(ev.lifts, CPS.inline { k1 =>
        val a = freshName("a")
        val ev = freshName("ev")
        mkLet(List(
          ml.Binding.FunBind(toML(kparam), List(ml.Param.Named(ev), ml.Param.Named(a)),
            ml.Call(ev)(ml.Call(k1.reify)(ml.Expr.Variable(a))))),
          toMLExpr(body).reify())
      })

    case Shift(_, _) => C.panic("Should not happen, body of shift is always a block lit with one parameter for the continuation.")

    case Region(body) =>
      CPS.inline { k => ml.Call(ml.Call(ml.Consts.withRegion)(toML(body)), List(k.reify)) }
  }

  def createBinder(id: Symbol, binding: Expr)(using TransformerContext): Binding = {
    ml.ValBind(name(id), toML(binding))
  }

  def createBinder(id: Symbol, binding: Block)(using TransformerContext): Binding = {
    binding match {
      case BlockLit(tparams, params, body) =>
        val k = freshName("k")
        ml.FunBind(name(id), params.map(p => ml.Param.Named(toML(p))) :+ ml.Param.Named(k), toMLExpr(body)(ml.Variable(k)))
      case _ =>
        ml.ValBind(name(id), toML(binding))
    }
  }

  def toML(defn: Definition)(using C: TransformerContext): ml.Binding = defn match {
    case Definition.Def(id, block) => createBinder(id, block)
    case Definition.Let(Wildcard(), binding) => ml.Binding.AnonBind(toML(binding))
    case Definition.Let(id, binding) => createBinder(id, binding)
  }

  def toML(block: BlockLit)(using TransformerContext): ml.Lambda = block match {
    case BlockLit(tparams, params, body) =>
      val k = freshName("k")
      ml.Lambda(params.map { p => ml.Param.Named(name(p.id)) } :+ ml.Param.Named(k), toMLExpr(body)(ml.Variable(k)))
  }

  def toML(block: Block)(using C: TransformerContext): ml.Expr = block match {
    case lifted.BlockVar(id, _) =>
      Variable(name(id))

    case b @ lifted.BlockLit(_, _, _) =>
      toML(b)

    case lifted.Member(b, field, annotatedType) =>
      ml.Call(C.accessorCache(field))(toML(b))

    case lifted.Unbox(e) => toML(e) // not sound

    case lifted.New(impl) => toML(impl)
  }

  def toML(impl: Implementation)(using TransformerContext): ml.Expr = impl match {
    case Implementation(interface, operations) =>
      ml.Expr.Make(interfaceNameFor(operations.size), expsToTupleIsh(operations map toML))
  }

  def toML(op: Operation)(using TransformerContext): ml.Expr = toML(op.implementation)

  def toML(scope: Evidence): ml.Expr = scope match {
    case Evidence(Nil) => Consts.here
    case Evidence(ev :: Nil) => toML(ev)
    case Evidence(scopes) =>
      scopes.map(toML).reduce(ml.Call(Consts.nested)(_, _))
  }

  def toML(l: Lift): ml.Expr = l match {
    case Lift.Try() => Consts.lift
    case Lift.Var(x) => Variable(name(x))
    case Lift.Reg() => effekt.util.messages.FIXME(Consts.lift, "Translate to proper lift on state")
  }

  def toML(expr: Expr)(using C: TransformerContext): ml.Expr = expr match {
    case l: Literal =>
      def numberString(x: AnyVal): ml.Expr = {
        val s = x.toString
        if (s.startsWith("-")) {
          ml.RawExpr(s"~${s.substring(1)}")
        } else ml.RawValue(s)
      }

      l.value match {
        case v: Byte => numberString(v)
        case v: Short => numberString(v)
        case v: Int => numberString(v)
        case v: Long => numberString(v)
        case v: Float => numberString(v)
        case v: Double => numberString(v)
        case _: Unit => Consts.unitVal
        case v: String => MLString(v)
        case v: Boolean => if (v) Consts.trueVal else Consts.falseVal
        case _ => ml.RawValue(l.value.toString)
      }
    case ValueVar(id, _) => ml.Variable(name(id))

    case PureApp(b, _, args) =>
      val mlArgs = args map {
        case e: Expr => toML(e)
        case b: Block => toML(b)
        case e: Evidence => toML(e)
      }
      b match {
        // TODO do not use symbols here, but look up in module declaration
        case BlockVar(id@symbols.Constructor(_, _, _, symbols.TypeConstructor.DataType(_, _, _)), _) =>
          ml.Expr.Make(name(id), expsToTupleIsh(mlArgs))
        case BlockVar(id@symbols.Constructor(_, _, _, symbols.TypeConstructor.Record(_, _, _)), _) =>
          ml.Expr.Make(name(id), expsToTupleIsh(mlArgs))
        case _ => ml.Call(toML(b), mlArgs)
      }

    case Select(b, field, _) =>
      ml.Call(name(field))(toML(b))

    case Run(s) => toMLExpr(s).run

    case Box(b) => toML(b) // not sound
  }

  enum Continuation {
    case Dynamic(cont: ml.Expr)
    case Static(cont: ml.Expr => ml.Expr)

    def apply(e: ml.Expr): ml.Expr = this match {
      case Continuation.Dynamic(k) => ml.Call(k)(e)
      case Continuation.Static(k) => k(e)
    }
    def reify: ml.Expr = this match {
      case Continuation.Dynamic(k) => k
      case Continuation.Static(k) =>
        val a = freshName("a")
        ml.Lambda(ml.Param.Named(a))(k(ml.Variable(a)))
    }
    def reflect: ml.Expr => ml.Expr = this match {
      case Continuation.Static(k) => k
      case Continuation.Dynamic(k) => a => ml.Call(k)(a)
    }
  }

  class CPS(prog: Continuation => ml.Expr) {
    def apply(k: Continuation): ml.Expr = prog(k)
    def apply(k: ml.Expr): ml.Expr = prog(Continuation.Dynamic(k))
    def apply(k: ml.Expr => ml.Expr): ml.Expr = prog(Continuation.Static(k))

    def flatMap(f: ml.Expr => CPS): CPS = CPS.inline(k => prog(Continuation.Static(a => f(a)(k))))
    def map(f: ml.Expr => ml.Expr): CPS = flatMap(a => CPS.pure(f(a)))
    def run: ml.Expr = prog(Continuation.Static(a => a))
    def reify(): ml.Expr =
      val k = freshName("k")
      ml.Lambda(ml.Param.Named(k))(this.apply(Continuation.Dynamic(ml.Expr.Variable(k))))
  }

  object CPS {

    def inline(prog: Continuation => ml.Expr): CPS = CPS(prog)
    def join(prog: Continuation => ml.Expr): CPS = CPS {
      case k: Continuation.Dynamic => prog(k)
      case k: Continuation.Static =>
        val kName = freshName("k")
        mkLet(List(ValBind(kName, k.reify)), prog(Continuation.Dynamic(ml.Variable(kName))))
    }

    def reset(prog: ml.Expr): ml.Expr =
      ml.Call(prog, List(pure))

    // fn a => fn k2 => k2(a)
    def pure: ml.Expr =
      val a = freshName("a")
      val k2 = freshName("k2")
      ml.Lambda(ml.Param.Named(a)) { ml.Lambda(ml.Param.Named(k2)) { ml.Call(ml.Variable(k2), List(ml.Variable(a))) }}

    def pure(expr: ml.Expr): CPS = CPS.inline(k => k(expr))

    def reflect(e: ml.Expr): CPS =
      CPS.join { k => ml.Call(e)(k.reify) }

    // [[ Try() ]] = m k1 k2 => m (fn a => k1 a k2);
    def lift(lifts: List[Lift], m: CPS): CPS = lifts match {

      // TODO implement lift for reg properly
      case (Lift.Try() | Lift.Reg()) :: lifts =>
        val k2 = freshName("k2")
        lift(lifts, CPS.inline { k1 => ml.Lambda(ml.Param.Named(k2)) {
          m.apply(a => ml.Call(k1.reify)(a, ml.Expr.Variable(k2)))
        }})

      // [[ [ev :: evs](m) ]] = ev([[ [evs](m) ]])
      case Lift.Var(x) :: lifts =>
        CPS.reflect(ml.Call(Variable(name(x)))(lift(lifts, m).reify()))

      case Nil => m
    }

    def runMain(main: MLName): ml.Expr = ml.Call(main)(id, id)

    def id =
      val a = MLName("a")
      ml.Lambda(ml.Param.Named(a))(ml.Variable(a))
  }

  def typesToTupleIsh(types: List[ml.Type]): Option[ml.Type] = types match {
    case Nil => None
    case one :: Nil => Some(one)
    case fieldTypes => Some(ml.Type.Tuple(fieldTypes))
  }

  def expsToTupleIsh(exps: List[ml.Expr]): Option[ml.Expr] = exps match {
    case Nil => None
    case one :: Nil => Some(one)
    case exps => Some(ml.Expr.Tuple(exps))
  }
}
