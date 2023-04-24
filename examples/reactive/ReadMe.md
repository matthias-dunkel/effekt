# Run examples
Open a terminal in the example folder. Run: `effekt reactive\examples`

# Synchronität
Synchron innerhalb des eventloops.
Aber nicht synchron außerhalb.

# Determinismus
Ist an sich nur semi-deterministisch, da das Halteproblem immer dazwischen liegt.
# Mututal Dependecies
Trails können auf die selbe resource zugreifen.
-> Trails müssen subregion der Region sein, in der die resource liegt.
