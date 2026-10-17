import ast, io
src = io.open("rea_mcp.py", encoding="utf-8").read()
ast.parse(src)
print("AST OK, bytes=" + str(len(src)))
