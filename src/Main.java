import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class Main {

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);

        // Serve the web page
        server.createContext("/", exchange -> {
            byte[] page = Files.readAllBytes(Path.of("src/web/index.html"));
            send(exchange, 200, "text/html; charset=utf-8", page);
        });

        // Compile endpoint: runs the phases and returns JSON
        server.createContext("/compile", exchange -> {
            String code = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            send(exchange, 200, "application/json; charset=utf-8",
                    compile(code).getBytes(StandardCharsets.UTF_8));
        });

        server.start();
        System.out.println("SYNTIQ compiler running at http://localhost:8080");
    }

    // Runs every phase and builds the JSON answer for the web page
    static String compile(String code) {
        // Phase 1: Lexical analysis
        Lexer lexer = new Lexer(code);
        List<Token> tokens = lexer.tokenize();
        List<Lexer.LexError> lexErrors = lexer.getErrors();

        String syntax;
        String semantic;
        String codegen;
        String execution;

        if (!lexErrors.isEmpty()) {
            syntax = skipped("Syntax analysis skipped: fix the lexical errors first.");
            semantic = skipped("Semantic analysis skipped: fix the lexical errors first.");
            codegen = skipped("Code generation skipped: fix the lexical errors first.");
            execution = skipped("Nothing to run: fix the lexical errors first.");
        } else {
            // Phase 2: Syntax analysis
            Parser parser = new Parser(tokens);
            Node tree = parser.parse();
            syntax = syntaxJson(tree, parser.getErrors());

            if (!parser.getErrors().isEmpty()) {
                semantic = skipped("Semantic analysis skipped: fix the syntax errors first.");
                codegen = skipped("Code generation skipped: fix the syntax errors first.");
                execution = skipped("Nothing to run: fix the syntax errors first.");
            } else {
                // Phase 3: Semantic analysis
                Analyzer analyzer = new Analyzer(tree);
                analyzer.analyze();
                semantic = semanticJson(analyzer);

                if (!analyzer.getErrors().isEmpty()) {
                    codegen = skipped("Code generation skipped: fix the semantic errors first.");
                    execution = skipped("Nothing to run: fix the semantic errors first.");
                } else {
                    // Phase 4: Intermediate code generation
                    List<CodeGen.Instr> instructions = new CodeGen(tree).generate();
                    codegen = codegenJson(instructions);

                    // Phase 5: Execution
                    execution = executionJson(new Interpreter().run(instructions));
                }
            }
        }

        return "{\"lexer\":" + lexerJson(tokens, lexErrors)
                + ",\"syntax\":" + syntax
                + ",\"semantic\":" + semantic
                + ",\"codegen\":" + codegen
                + ",\"execution\":" + execution + "}";
    }

    private static String skipped(String message) {
        return "{\"skipped\":true,\"message\":\"" + esc(message) + "\"}";
    }

    private static void send(com.sun.net.httpserver.HttpExchange exchange, int status,
                             String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    // ---------- Tiny hand-written JSON builders (no libraries needed) ----------
    private static String lexerJson(List<Token> tokens, List<Lexer.LexError> errors) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"tokens\":[");
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"type\":\"").append(t.type()).append("\"")
              .append(",\"lexeme\":\"").append(esc(t.lexeme())).append("\"")
              .append(",\"line\":").append(t.line())
              .append(",\"column\":").append(t.column()).append("}");
        }
        sb.append("],\"errors\":[");
        for (int i = 0; i < errors.size(); i++) {
            Lexer.LexError e = errors.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"line\":").append(e.line())
              .append(",\"column\":").append(e.column())
              .append(",\"message\":\"").append(esc(e.message())).append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String syntaxJson(Node tree, List<Parser.SyntaxError> errors) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"skipped\":false,\"tree\":\"").append(esc(tree.toTree())).append("\",\"errors\":[");
        for (int i = 0; i < errors.size(); i++) {
            Parser.SyntaxError e = errors.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"line\":").append(e.line())
              .append(",\"column\":").append(e.column())
              .append(",\"message\":\"").append(esc(e.message())).append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String semanticJson(Analyzer a) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"skipped\":false,\"errors\":").append(messagesJson(a.getErrors()));
        sb.append(",\"warnings\":").append(messagesJson(a.getWarnings()));
        sb.append(",\"symbols\":[");
        List<Analyzer.Symbol> symbols = a.getSymbols();
        for (int i = 0; i < symbols.size(); i++) {
            Analyzer.Symbol sym = symbols.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"name\":\"").append(esc(sym.name)).append("\"")
              .append(",\"type\":\"").append(esc(sym.type)).append("\"")
              .append(",\"scope\":\"").append(esc(sym.scope)).append("\"")
              .append(",\"line\":").append(sym.line)
              .append(",\"used\":").append(sym.used)
              .append(",\"param\":").append(sym.isParam).append("}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String codegenJson(List<CodeGen.Instr> code) {
        StringBuilder sb = new StringBuilder("{\"skipped\":false,\"lines\":[");
        for (int i = 0; i < code.size(); i++) {
            CodeGen.Instr in = code.get(i);
            if (i > 0) sb.append(",");
            String line = in.op().equals("label") ? in.toString() : "    " + in;
            sb.append("\"").append(esc(line)).append("\"");
        }
        return sb.append("]}").toString();
    }

    private static String executionJson(Interpreter.Result r) {
        StringBuilder sb = new StringBuilder("{\"skipped\":false,\"output\":[");
        for (int i = 0; i < r.output.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(esc(r.output.get(i))).append("\"");
        }
        sb.append("],\"steps\":").append(r.steps);
        sb.append(",\"error\":");
        sb.append(r.error == null ? "null" : "\"" + esc(r.error) + "\"");
        return sb.append("}").toString();
    }

    private static String messagesJson(List<Analyzer.SemError> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            Analyzer.SemError e = items.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"line\":").append(e.line())
              .append(",\"message\":\"").append(esc(e.message())).append("\"}");
        }
        return sb.append("]").toString();
    }

    private static String esc(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}