import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Phase 4: turns the checked parse tree into three-address code.
public class CodeGen {

    // One three-address instruction
    public record Instr(String op, String dest, String a, String b) {

        public static final Set<String> BINARY = Set.of("+", "-", "*", "/", "%", "<", ">", "==", "!=");

        @Override
        public String toString() {
            if (BINARY.contains(op)) {
                return dest + " = " + a + " " + op + " " + b;
            }
            switch (op) {
                case "label":
                    return dest + ":";
                case "=":
                    return dest + " = " + a;
                case "decl":
                    return "declare " + dest + " : " + a;
                case "neg":
                    return dest + " = - " + a;
                case "not":
                    return dest + " = ! " + a;
                case "goto":
                    return "goto " + dest;
                case "if_false":
                    return "if_false " + a + " goto " + dest;
                case "if_true":
                    return "if_true " + a + " goto " + dest;
                case "print":
                    return "print " + a;
                case "return":
                    // 'b' secretly carries the method's return type for the interpreter,
                    // so it is deliberately not printed here
                    return a == null ? "return" : "return " + a;
                case "halt":
                    return "halt";
                case "param":
                    return "param " + a;
                case "call":
                    return (dest == null ? "" : dest + " = ") + "call " + a + ", " + b;
                case "arg":
                    return "arg " + dest + " : " + a;
                default:
                    return op;
            }
        }
    }

    // SYNTIQ operator -> operator shown in the three-address code
    private static final Map<String, String> OPS = Map.of(
            "~+", "+", "~-", "-", "~*", "*", "~/", "/", "~%", "%",
            "~>", ">", "~<", "<", "~=", "==", "~!", "!=");

    private final Node root;
    private final List<Instr> code = new ArrayList<>();
    private int tempCount = 0;
    private int labelCount = 0;
    // For each enclosing loop: { label for 'skip', label for 'stop' }
    private final Deque<String[]> loops = new ArrayDeque<>();
    private String currentReturn = "empty"; // NEW: return type of the method being generated

    public CodeGen(Node root) {
        this.root = root;
    }

    public List<Instr> generate() {
        for (Node blueprint : root.children) {
            for (Node method : blueprint.children) {
                method(method);
            }
        }
        return code;
    }

    // =====================================================================
    //  Methods and statements
    // =====================================================================
    private void method(Node m) {
        String name = "?";
        Node params = null;
        Node body = null;
        currentReturn = "empty"; // NEW
        for (Node c : m.children) {
            if (c.type.equals("Name")) {
                name = c.value;
            } else if (c.type.equals("Type")) { // NEW: the method's return type
                currentReturn = category(c.value);
            } else if (c.type.equals("Parameters")) {
                params = c;
            } else if (c.type.equals("Body")) {
                body = c;
            }
        }
        loops.clear();
        emit("label", name, null, null);

        // bind each incoming argument to its parameter name
        if (params != null) {
            int index = 0;
            for (Node p : params.children) {
                String ptype = category(p.children.get(0).value);
                String pname = p.children.get(1).value;
                emit("arg", pname, ptype, String.valueOf(index));
                index++;
            }
        }

        if (body != null) {
            block(body);
        }
        emit("return", null, null, null); // every method ends with an implicit return
    }

    private void block(Node b) {
        for (Node st : b.children) {
            statement(st);
        }
    }

    private void statement(Node s) {
        switch (s.type) {
            case "Declaration" -> {
                emit("decl", s.value, category(s.children.get(0).value), null);
                if (s.children.size() > 1) {
                    emit("=", s.value, expr(s.children.get(1)), null);
                }
            }
            case "Assignment" -> emit("=", s.value, expr(s.children.get(0)), null);
            case "Show" -> emit("print", null, expr(s.children.get(0)), null);
            case "CallStmt" -> call(s.children.get(0), false); // result is thrown away
            case "If" -> ifStatement(s);
            case "While" -> whileStatement(s);
            case "For" -> forStatement(s);
            case "Skip" -> {
                if (!loops.isEmpty()) {
                    emit("goto", loops.peek()[0], null, null);
                }
            }
            case "Stop" -> {
                // inside a loop: leave the loop. Outside any loop: stop the program.
                if (loops.isEmpty()) {
                    emit("halt", null, null, null);
                } else {
                    emit("goto", loops.peek()[1], null, null);
                }
            }
            case "Return" -> {
                String value = s.children.isEmpty() ? null : expr(s.children.get(0));
                emit("return", null, value, currentReturn); // NEW: carry the return type along
            }
            case "Block" -> block(s);
            default -> {
            }
        }
    }

    private void ifStatement(Node s) {
        Node cond = null;
        Node thenNode = null;
        Node elseNode = null;
        for (Node c : s.children) {
            switch (c.type) {
                case "Condition" -> cond = c;
                case "Then" -> thenNode = c;
                case "Else" -> elseNode = c;
                default -> {
                }
            }
        }

        String lElse = newLabel();
        emit("if_false", lElse, expr(cond.children.get(0)), null);
        block(thenNode);
        if (elseNode != null) {
            String lEnd = newLabel();
            emit("goto", lEnd, null, null);
            emit("label", lElse, null, null);
            block(elseNode);
            emit("label", lEnd, null, null);
        } else {
            emit("label", lElse, null, null);
        }
    }

    private void whileStatement(Node s) {
        Node cond = null;
        Node body = null;
        for (Node c : s.children) {
            if (c.type.equals("Condition")) {
                cond = c;
            } else if (c.type.equals("Body")) {
                body = c;
            }
        }

        String lStart = newLabel();
        String lEnd = newLabel();
        emit("label", lStart, null, null);
        emit("if_false", lEnd, expr(cond.children.get(0)), null);
        loops.push(new String[] {lStart, lEnd});
        block(body);
        loops.pop();
        emit("goto", lStart, null, null);
        emit("label", lEnd, null, null);
    }

    private void forStatement(Node s) {
        Node init = null;
        Node cond = null;
        Node update = null;
        Node body = null;
        for (Node c : s.children) {
            switch (c.type) {
                case "Init" -> init = c;
                case "Condition" -> cond = c;
                case "Update" -> update = c;
                case "Body" -> body = c;
                default -> {
                }
            }
        }

        String lCond = newLabel();
        String lUpdate = newLabel();
        String lEnd = newLabel();

        statement(init.children.get(0));
        emit("label", lCond, null, null);
        emit("if_false", lEnd, expr(cond.children.get(0)), null);
        loops.push(new String[] {lUpdate, lEnd}); // 'skip' must still run the update step
        block(body);
        loops.pop();
        emit("label", lUpdate, null, null);
        statement(update.children.get(0));
        emit("goto", lCond, null, null);
        emit("label", lEnd, null, null);
    }

    // =====================================================================
    //  Expressions: each returns the name of the place holding the result
    //  (a literal, a variable, or a new temporary such as t3)
    // =====================================================================
    private String expr(Node e) {
        return switch (e.type) {
            case "IntLiteral", "DecimalLiteral", "StringLiteral", "CharLiteral",
                 "BoolLiteral", "NilLiteral", "Identifier" -> e.value;
            case "UnaryOp" -> {
                String a = expr(e.children.get(0));
                String t = newTemp();
                emit(e.value.equals("negate") ? "not" : "neg", t, a, null);
                yield t;
            }
            case "BinaryOp" -> binary(e);
            case "Call" -> call(e, true); // a call used as a value
            default -> "nil";
        };
    }

    // emits  param ... param  call
    // Every argument is worked out FIRST, so that a nested call such as
    // f(1, g(2)) cannot mix its own 'param' lines in between f's.
    private String call(Node c, boolean wantResult) {
        List<String> args = new ArrayList<>();
        for (Node arg : c.children) {
            args.add(expr(arg));
        }
        for (String a : args) {
            emit("param", null, a, null);
        }
        String result = wantResult ? newTemp() : null;
        emit("call", result, c.value, String.valueOf(args.size()));
        return result;
    }

    private String binary(Node e) {
        String op = e.value;

        // 'both' and 'either' skip the right side when the left side already decides the answer
        if (op.equals("both") || op.equals("either")) {
            String left = expr(e.children.get(0));
            String result = newTemp();
            String lEnd = newLabel();
            emit("=", result, left, null);
            emit(op.equals("both") ? "if_false" : "if_true", lEnd, result, null);
            emit("=", result, expr(e.children.get(1)), null);
            emit("label", lEnd, null, null);
            return result;
        }

        String left = expr(e.children.get(0));
        String right = expr(e.children.get(1));
        String t = newTemp();
        emit(OPS.get(op), t, left, right);
        return t;
    }

    // =====================================================================
    //  Helpers
    // =====================================================================
    private void emit(String op, String dest, String a, String b) {
        code.add(new Instr(op, dest, a, b));
    }

    private String newTemp() {
        return "t" + (++tempCount);
    }

    private String newLabel() {
        return "L" + (++labelCount);
    }

    private String category(String type) {
        switch (type) {
            case "whole", "bigwhole", "smallwhole", "tinywhole":
                return "whole";
            case "decimal", "shortdecimal":
                return "decimal";
            default:
                return type;
        }
    }
}