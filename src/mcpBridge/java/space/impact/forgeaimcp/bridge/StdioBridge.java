package space.impact.forgeaimcp.bridge;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Standalone MCP process. Never launches Minecraft or enables the game listener. */
public final class StdioBridge {
    private static final int MAX_REQUEST = 65536;
    private static final int MAX_RESPONSE = 8 * 1024 * 1024;
    private static final String[] VERSIONS = {"2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25"};
    private final int port;
    private final JsonArray tools = tools();
    private boolean negotiated;
    private boolean initialized;

    private StdioBridge(int port) { this.port = port; }

    public static void main(String[] args) throws IOException {
        int port = 25576;
        if (args.length != 0) {
            if (args.length != 2 || !"--port".equals(args[0])) {
                System.err.println("Usage: java -jar forgemcp-stdio.jar [--port PORT]");
                System.exit(2);
            }
            try { port = Integer.parseInt(args[1]); }
            catch (NumberFormatException e) { port = -1; }
        }
        if (port < 1024 || port > 65535) {
            System.err.println("Port must be between 1024 and 65535");
            System.exit(2);
        }
        StdioBridge server = new StdioBridge(port);
        InputStream input = new BufferedInputStream(System.in);
        Writer output = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
        while (true) {
            JsonObject response;
            try {
                String line = readLine(input, MAX_REQUEST);
                if (line == null) return;
                try { response = server.dispatch(new JsonParser().parse(line)); }
                catch (JsonParseException e) { response = error(JsonNull.INSTANCE, -32700, "Invalid JSON"); }
            } catch (MessageTooLarge e) {
                response = error(JsonNull.INSTANCE, -32600, "Message exceeds 64 KiB");
            }
            if (response != null) {
                output.write(response.toString());
                output.write('\n');
                output.flush();
            }
        }
    }

    private JsonObject dispatch(JsonElement value) {
        if (!value.isJsonObject()) return error(JsonNull.INSTANCE, -32600, "Invalid JSON-RPC request");
        JsonObject message = value.getAsJsonObject();
        JsonElement id = message.has("id") ? message.get("id") : JsonNull.INSTANCE;
        if (!isString(message.get("jsonrpc")) || !"2.0".equals(message.get("jsonrpc").getAsString())
                || !isString(message.get("method"))) return error(JsonNull.INSTANCE, -32600, "Invalid JSON-RPC request");
        String method = message.get("method").getAsString();
        if (!message.has("id")) {
            if ("notifications/initialized".equals(method) && negotiated) initialized = true;
            return null;
        }
        if (!isString(id) && !isInteger(id)) return error(JsonNull.INSTANCE, -32600, "Invalid request ID");
        try {
            JsonObject params = object(message, "params");
            JsonObject result;
            switch (method) {
                case "initialize":
                    if (negotiated) throw new RpcError(-32600, "Already initialized");
                    String version = string(params, "protocolVersion");
                    if (!params.has("capabilities") || !params.get("capabilities").isJsonObject()
                            || !params.has("clientInfo") || !params.get("clientInfo").isJsonObject())
                        throw new RpcError(-32602, "Missing capabilities or clientInfo");
                    negotiated = true;
                    result = new JsonObject();
                    result.addProperty("protocolVersion", Arrays.asList(VERSIONS).contains(version) ? version : VERSIONS[VERSIONS.length - 1]);
                    JsonObject capabilities = new JsonObject();
                    JsonObject toolCapabilities = new JsonObject();
                    toolCapabilities.addProperty("listChanged", false);
                    capabilities.add("tools", toolCapabilities);
                    result.add("capabilities", capabilities);
                    JsonObject info = new JsonObject();
                    info.addProperty("name", "forgemcp");
                    info.addProperty("version", "0.2.0");
                    result.add("serverInfo", info);
                    result.addProperty("instructions", "Join a world and run /forgemcp start. All tools are read-only.");
                    break;
                case "ping": result = new JsonObject(); break;
                default:
                    if (!initialized) throw new RpcError(-32002, "Initialize the MCP session first");
                    if ("tools/list".equals(method)) {
                        result = new JsonObject();
                        result.add("tools", tools);
                    } else if ("tools/call".equals(method)) {
                        String name = string(params, "name");
                        JsonObject arguments = object(params, "arguments");
                        validate(name, arguments);
                        try { result = content(gameCall(name, arguments), false); }
                        catch (IOException e) {
                            JsonObject failure = new JsonObject();
                            failure.addProperty("error", "Game bridge unavailable. Run /forgemcp start. " + e.getMessage());
                            if ("get_status".equals(name)) {
                                failure.addProperty("running", false);
                                failure.addProperty("address", "127.0.0.1:" + port);
                            }
                            result = content(failure, !"get_status".equals(name));
                        } catch (GameError e) {
                            JsonObject failure = new JsonObject();
                            failure.addProperty("error", e.getMessage());
                            result = content(failure, true);
                        }
                    } else throw new RpcError(-32601, "Unknown method: " + method);
            }
            JsonObject response = envelope(id);
            response.add("result", result);
            return response;
        } catch (RpcError e) { return error(id, e.code, e.getMessage()); }
    }

    private JsonObject gameCall(String name, JsonObject arguments) throws IOException, GameError {
        JsonObject request = new JsonObject();
        request.addProperty("tool", name);
        request.add("arguments", arguments);
        byte[] payload = (request.toString() + "\n").getBytes(StandardCharsets.UTF_8);
        if (payload.length > MAX_REQUEST) throw new GameError("Game request exceeds 64 KiB");
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            socket.setSoTimeout(15000);
            socket.getOutputStream().write(payload);
            String raw = readLine(new BufferedInputStream(socket.getInputStream()), MAX_RESPONSE);
            if (raw == null) throw new GameError("Missing game response");
            JsonElement parsed = new JsonParser().parse(raw);
            if (!parsed.isJsonObject()) throw new GameError("Invalid game response");
            JsonObject response = parsed.getAsJsonObject();
            if (response.has("error")) throw new GameError(response.get("error").getAsString());
            if (!response.has("result") || !response.get("result").isJsonObject()) throw new GameError("Missing game result");
            return response.getAsJsonObject("result");
        } catch (JsonParseException | IllegalStateException e) { throw new GameError("Invalid game response"); }
    }

    private void validate(String name, JsonObject arguments) throws RpcError {
        JsonObject spec = null;
        for (JsonElement entry : tools) {
            if (name.equals(entry.getAsJsonObject().get("name").getAsString()))
                spec = entry.getAsJsonObject().getAsJsonObject("inputSchema");
        }
        if (spec == null) throw new RpcError(-32602, "Unknown tool: " + name);
        for (JsonElement key : spec.getAsJsonArray("required")) {
            if (!arguments.has(key.getAsString())) throw new RpcError(-32602, "Missing argument: " + key.getAsString());
        }
        JsonObject properties = spec.getAsJsonObject("properties");
        for (java.util.Map.Entry<String, JsonElement> entry : arguments.entrySet()) {
            String key = entry.getKey();
            if (!properties.has(key)) throw new RpcError(-32602, "Unknown argument: " + key);
            JsonObject prop = properties.getAsJsonObject(key);
            if ("string".equals(prop.get("type").getAsString())) {
                if (!isString(entry.getValue())) throw new RpcError(-32602, key + " must be a string");
            } else if ("boolean".equals(prop.get("type").getAsString())) {
                if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isBoolean())
                    throw new RpcError(-32602, key + " must be a boolean");
            } else {
                if (!isInteger(entry.getValue())) throw new RpcError(-32602, key + " must be an integer");
                java.math.BigDecimal number = entry.getValue().getAsBigDecimal();
                if (number.compareTo(prop.get("minimum").getAsBigDecimal()) < 0
                        || number.compareTo(prop.get("maximum").getAsBigDecimal()) > 0)
                    throw new RpcError(-32602, key + " is out of range");
            }
        }
    }

    private static JsonArray tools() {
        JsonArray list = new JsonArray();
        addTool(list, "get_status", "Check bridge connectivity and game readiness. Does not start the bridge.", false, false, "");
        addTool(list, "search_items", "Search NEI items by localized name or registry ID.", false, true, "query,mod");
        addTool(list, "get_item", "Get item identity, SNBT, tooltip and OreDictionary memberships.", true, false, "");
        addTool(list, "get_recipes", "NEI recipes producing an item, with ingredient alternatives and other stacks.", true, true, "handler");
        addTool(list, "get_usages", "NEI recipes using an item as an ingredient.", true, true, "handler");
        addTool(list, "search_oredict", "Search OreDictionary group names.", false, true, "query");
        addTool(list, "get_oredict", "Stacks in an exact OreDictionary group, preserving wildcard metadata.", false, true, "name");
        return list;
    }

    private static void addTool(JsonArray list, String name, String description, boolean item, boolean page, String strings) {
        JsonObject tool = new JsonObject();
        tool.addProperty("name", name);
        tool.addProperty("description", description);
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty("additionalProperties", false);
        JsonObject properties = new JsonObject();
        JsonArray required = new JsonArray();
        if (item) {
            properties.add("item", property("string"));
            properties.add("metadata", integer(0, 32767, 0));
            properties.add("nbt", property("string"));
            required.add(new JsonPrimitive("item"));
        }
        if (page) {
            properties.add("offset", integer(0, Integer.MAX_VALUE, 0));
            properties.add("limit", integer(1, 100, 20));
        }
        if (!strings.isEmpty()) for (String key : strings.split(",")) properties.add(key, property("string"));
        if ("get_oredict".equals(name)) required.add(new JsonPrimitive("name"));
        schema.add("properties", properties);
        schema.add("required", required);
        tool.add("inputSchema", schema);
        JsonObject hints = new JsonObject();
        hints.addProperty("readOnlyHint", true);
        hints.addProperty("destructiveHint", false);
        hints.addProperty("idempotentHint", true);
        hints.addProperty("openWorldHint", false);
        tool.add("annotations", hints);
        list.add(tool);
    }

    private static JsonObject property(String type) {
        JsonObject prop = new JsonObject(); prop.addProperty("type", type); return prop;
    }
    private static JsonObject integer(int min, int max, int value) {
        JsonObject prop = property("integer");
        prop.addProperty("minimum", min); prop.addProperty("maximum", max); prop.addProperty("default", value); return prop;
    }
    private static boolean isString(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
    }
    private static boolean isInteger(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return false;
        try { return value.getAsBigDecimal().stripTrailingZeros().scale() <= 0; }
        catch (NumberFormatException e) { return false; }
    }
    private static String string(JsonObject obj, String key) throws RpcError {
        if (!isString(obj.get(key))) throw new RpcError(-32602, key + " must be a string");
        return obj.get(key).getAsString();
    }
    private static JsonObject object(JsonObject obj, String key) throws RpcError {
        if (!obj.has(key)) return new JsonObject();
        if (!obj.get(key).isJsonObject()) throw new RpcError(-32602, key + " must be an object");
        return obj.getAsJsonObject(key);
    }
    private static JsonObject envelope(JsonElement id) {
        JsonObject response = new JsonObject(); response.addProperty("jsonrpc", "2.0"); response.add("id", id); return response;
    }
    private static JsonObject error(JsonElement id, int code, String message) {
        JsonObject response = envelope(id); JsonObject error = new JsonObject();
        error.addProperty("code", code); error.addProperty("message", message); response.add("error", error); return response;
    }
    private static JsonObject content(JsonObject data, boolean failed) {
        JsonObject result = new JsonObject(); JsonArray content = new JsonArray(); JsonObject text = new JsonObject();
        text.addProperty("type", "text"); text.addProperty("text", data.toString()); content.add(text);
        result.add("content", content); result.addProperty("isError", failed);
        if (!failed) result.add("structuredContent", data);
        return result;
    }
    private static String readLine(InputStream input, int max) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        boolean tooLarge = false;
        int c;
        while ((c = input.read()) != -1 && c != '\n') {
            if (bytes.size() >= max) tooLarge = true;
            if (!tooLarge) bytes.write(c);
        }
        if (tooLarge) throw new MessageTooLarge();
        if (c == -1 && bytes.size() == 0) return null;
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }
    private static final class MessageTooLarge extends IOException { }
    private static final class GameError extends Exception { GameError(String message) { super(message); } }
    private static final class RpcError extends Exception {
        final int code; RpcError(int code, String message) { super(message); this.code = code; }
    }
}
