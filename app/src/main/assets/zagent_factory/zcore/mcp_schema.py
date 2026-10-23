"""zcore.mcp_schema - tolerant MCP tool schema handling (task 33).

Rules:

- Never drop a tool because its inputSchema uses composition
 keywords ($ref, anyOf, oneOf, allOf, not, if/then/else,
 dependentSchemas, prefixItems, ...). Those are the MCP server's
 business to interpret; the client only needs name + schema.
- Only name must be a non-empty string. Everything else is
 optional and preserved verbatim.
- x-mcp-header maps an argument name to an HTTP header name so the
 transport can lift it out of the JSON body.
"""

COMPOSITION_KEYS = (
 "$ref", "dynamicRef", "anyOf", "oneOf", "allOf", "not",
 "if", "then", "else", "dependentSchemas", "dependentRequired",
 "prefixItems", "contains", "propertyNames", "unevaluatedItems",
 "unevaluatedProperties", "$defs", "definitions",
)


def normalize_tool(raw):
 if not isinstance(raw, dict):
  return None
 name = raw.get("name")
 if not isinstance(name, str) or not name.strip():
  return None
 schema = raw.get("inputSchema")
 if not isinstance(schema, dict):
  schema = {}
 out = {
  "name": name.strip(),
  "description": str(raw.get("description") or ""),
  "inputSchema": schema,
 }
 hdr = raw.get("x-mcp-header")
 if isinstance(hdr, dict):
  out["x-mcp-header"] = {str(k): str(v) for k, v in hdr.items()
               if isinstance(k, str) and isinstance(v, str)}
 return out


def normalize_tools(items):
 out = []
 seen = set()
 if not isinstance(items, list):
  return out
 for raw in items:
  t = normalize_tool(raw)
  if t is None:
   continue
  if t["name"] in seen:
   continue
  seen.add(t["name"])
  out.append(t)
 return out


def uses_composition(schema):
 if not isinstance(schema, dict):
  return False
 for k in COMPOSITION_KEYS:
  if k in schema:
   return True
 props = schema.get("properties")
 if isinstance(props, dict):
  for v in props.values():
   if uses_composition(v):
    return True
 items = schema.get("items")
 if isinstance(items, dict) and uses_composition(items):
  return True
 return False


def request_headers(tool, args):
 if not isinstance(tool, dict):
  return {}
 mapping = tool.get("x-mcp-header")
 if not isinstance(mapping, dict) or not isinstance(args, dict):
  return {}
 out = {}
 for arg_name, header_name in mapping.items():
  if arg_name in args and args[arg_name] is not None:
   out[header_name] = str(args[arg_name])
 return out
