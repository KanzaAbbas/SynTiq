import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// Phase 5: runs the three-address code and collects what 'show' prints.
public class Interpreter {

    public static final int MAX_STEPS = 100000;
    public static final int MAX_DEPTH = 200; // deepest allowed chain of nested calls

    public static class Result {
        public final List<String> output = new ArrayList<>();
        public String error = null;
        public int steps = 0;
    }

    private static class RunError extends RuntimeException {
        private static final long serialVersionUID = 1L;

        RunError(String message) {
            super(message);
        }
    }

    // One activation record = everything a single method call needs
    private static class Frame {
        final Map<String, Object> vars = new HashMap<>();   // variables and temporaries
        final Map<String, String> types = new HashMap<>();  // declared type of each variable
        final List<Object> incoming;                        // argument values handed in by the caller
        final int returnPc;                                 // where to continue after this call ends
        final String resultDest;                            // caller's temporary that receives the result

        Frame(List<Object> incoming, int returnPc, String resultDest) {
            this.incoming = incoming;
            this.returnPc = returnPc;
            this.resultDest = resultDest;
        }
    }

    public Result run(List<CodeGen.Instr> code) {
        Result result = new Result();

        // Where is each label?
        Map<String, Integer> labels = new HashMap<>();
        for (int i = 0; i < code.size(); i++) {
            if (code.get(i).op().equals("label")) {
                labels.put(code.get(i).dest(), i);
            }
        }
        Integer entry = labels.get("start");
        if (entry == null) {
            result.error = "There is no 'start' method to run";
            return result;
        }

        // The call stack. The first frame belongs to 'start'.
        Deque<Frame> stack = new ArrayDeque<>();
        stack.push(new Frame(new ArrayList<>(), code.size(), null));
        List<Object> pending = new ArrayList<>();     // arguments waiting for their 'call'
        int pc = entry;                               // program counter

        try {
            while (pc < code.size()) {
                if (++result.steps > MAX_STEPS) {
                    throw new RunError("Stopped after " + MAX_STEPS
                            + " steps: the program may contain an infinite loop");
                }
                CodeGen.Instr in = code.get(pc);
                pc++;

                Frame f = stack.peek(); // the frame that is running right now
                Map<String, Object> vars = f.vars;
                Map<String, String> types = f.types;

                switch (in.op()) {
                    case "label":
                        break;
                    case "decl":
                        types.put(in.dest(), in.a());
                        vars.put(in.dest(), defaultValue(in.a()));
                        break;
                    case "=": {
                        Object v = value(in.a(), vars);
                        // a decimal variable always holds a decimal, even if given a whole number
                        if ("decimal".equals(types.get(in.dest())) && v instanceof Long l) {
                            v = l.doubleValue();
                        }
                        vars.put(in.dest(), v);
                        break;
                    }
                    case "neg": {
                        Object v = value(in.a(), vars);
                        if (v instanceof Long l) {
                            vars.put(in.dest(), -l);
                        } else {
                            vars.put(in.dest(), -((Number) v).doubleValue());
                        }
                        break;
                    }
                    case "not":
                        vars.put(in.dest(), !truth(value(in.a(), vars)));
                        break;
                    case "goto":
                        pc = labels.get(in.dest());
                        break;
                    case "if_false":
                        if (!truth(value(in.a(), vars))) {
                            pc = labels.get(in.dest());
                        }
                        break;
                    case "if_true":
                        if (truth(value(in.a(), vars))) {
                            pc = labels.get(in.dest());
                        }
                        break;
                    case "print":
                        result.output.add(text(value(in.a(), vars)));
                        break;

                    // remember one argument value until its 'call' arrives
                    case "param":
                        pending.add(value(in.a(), vars));
                        break;

                    // push a new frame and jump into the method
                    case "call": {
                        String name = in.a();
                        int count = Integer.parseInt(in.b());
                        Integer target = labels.get(name);
                        if (target == null) {
                            throw new RunError("Method '" + name + "' was not found");
                        }
                        if (pending.size() < count) {
                            throw new RunError("Internal error: missing arguments for '" + name + "'");
                        }
                        if (stack.size() >= MAX_DEPTH) {
                            throw new RunError("Too many nested calls (over " + MAX_DEPTH
                                    + "): the program may contain infinite recursion");
                        }
                        // take the last 'count' pending values as this call's arguments
                        int from = pending.size() - count;
                        List<Object> args = new ArrayList<>(pending.subList(from, pending.size()));
                        pending.subList(from, pending.size()).clear();

                        stack.push(new Frame(args, pc, in.dest()));
                        pc = target;
                        break;
                    }

                    // the callee copies its incoming argument into a named local variable
                    case "arg": {
                        int index = Integer.parseInt(in.b());
                        if (index >= f.incoming.size()) {
                            throw new RunError("Internal error: argument " + (index + 1) + " is missing");
                        }
                        Object v = f.incoming.get(index);
                        if ("decimal".equals(in.a()) && v instanceof Long l) {
                            v = l.doubleValue();
                        }
                        types.put(in.dest(), in.a());
                        vars.put(in.dest(), v);
                        break;
                    }

                    // leave the current method
                    case "return": {
                        Object returned = in.a() == null ? null : value(in.a(), vars);
                        // NEW: a method declared 'decimal' always gives back a decimal,
                        // even when the giveback expression is a whole number
                        if ("decimal".equals(in.b()) && returned instanceof Long l) {
                            returned = l.doubleValue();
                        }
                        if (stack.size() == 1) {
                            return result; // 'start' finished: the whole program is done
                        }
                        stack.pop();
                        if (f.resultDest != null) {
                            stack.peek().vars.put(f.resultDest, returned);
                        }
                        pc = f.returnPc;
                        break;
                    }

                    case "halt":
                        return result; // 'stop' outside a loop ends the whole program
                    default:
                        vars.put(in.dest(), binary(in.op(), value(in.a(), vars), value(in.b(), vars)));
                        break;
                }
            }
        } catch (RunError e) {
            result.error = e.getMessage();
        }
        return result;
    }

    // =====================================================================
    //  Values
    // =====================================================================
    private Object value(String operand, Map<String, Object> vars) {
        if (operand == null || operand.equals("nil")) {
            return null;
        }
        if (operand.equals("valid")) {
            return Boolean.TRUE;
        }
        if (operand.equals("invalid")) {
            return Boolean.FALSE;
        }
        char first = operand.charAt(0);
        if (first == '"') {
            return unescape(operand);
        }
        if (first == '\'') {
            String s = unescape(operand);
            return s.isEmpty() ? ' ' : s.charAt(0);
        }
        if (Character.isDigit(first)) {
            try {
                return operand.contains(".") ? (Object) Double.parseDouble(operand) : (Object) Long.parseLong(operand);
            } catch (NumberFormatException e) {
                throw new RunError("The number " + operand + " is too large");
            }
        }
        if (!vars.containsKey(operand)) {
            throw new RunError("'" + operand + "' has no value yet");
        }
        return vars.get(operand);
    }

    private Object defaultValue(String type) {
        switch (type) {
            case "whole":
                return 0L;
            case "decimal":
                return 0.0;
            case "text":
                return "";
            case "letter":
                return ' ';
            case "truth":
                return Boolean.FALSE;
            default:
                return null;
        }
    }

    // Removes the quotes of a text or letter literal and handles \n, \t, \", \\
    private String unescape(String quoted) {
        String body = quoted.substring(1, quoted.length() - 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\\' && i + 1 < body.length()) {
                char next = body.charAt(++i);
                switch (next) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case '"' -> sb.append('"');
                    case '\'' -> sb.append('\'');
                    case '\\' -> sb.append('\\');
                    default -> sb.append(next);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private boolean truth(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        throw new RunError("A condition did not produce a truth value");
    }

    // How 'show' and text joining display a value
    private String text(Object v) {
        if (v == null) {
            return "nil";
        }
        if (v instanceof Boolean b) {
            return b ? "valid" : "invalid";
        }
        return String.valueOf(v);
    }

    // =====================================================================
    //  Operators
    // =====================================================================
    private Object binary(String op, Object a, Object b) {
        switch (op) {
            case "+":
                if (a instanceof String || b instanceof String) {
                    return text(a) + text(b); // text joining
                }
                return arithmetic(op, a, b);
            case "-":
            case "*":
            case "/":
            case "%":
                return arithmetic(op, a, b);
            case "<":
            case ">":
                return compare(op, a, b);
            case "==":
                return equal(a, b);
            case "!=":
                return !equal(a, b);
            default:
                throw new RunError("Unknown operator " + op);
        }
    }

    private Object arithmetic(String op, Object a, Object b) {
        if (!(a instanceof Number) || !(b instanceof Number)) {
            throw new RunError("Arithmetic needs two numbers");
        }
        if ((op.equals("/") || op.equals("%")) && ((Number) b).doubleValue() == 0.0) {
            throw new RunError("Division by zero while running the program");
        }
        if (a instanceof Long x && b instanceof Long y) {
            switch (op) {
                case "+":
                    return x + y;
                case "-":
                    return x - y;
                case "*":
                    return x * y;
                case "/":
                    return x / y; // whole division, like Java
                default:
                    return x % y;
            }
        }
        double x = ((Number) a).doubleValue();
        double y = ((Number) b).doubleValue();
        switch (op) {
            case "+":
                return x + y;
            case "-":
                return x - y;
            case "*":
                return x * y;
            case "/":
                return x / y;
            default:
                return x % y;
        }
    }

    private Object compare(String op, Object a, Object b) {
        double x;
        double y;
        if (a instanceof Number n1 && b instanceof Number n2) {
            x = n1.doubleValue();
            y = n2.doubleValue();
        } else if (a instanceof Character c1 && b instanceof Character c2) {
            x = c1;
            y = c2;
        } else {
            throw new RunError("These values cannot be compared");
        }
        return op.equals("<") ? x < y : x > y;
    }

    private boolean equal(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return x.doubleValue() == y.doubleValue();
        }
        return Objects.equals(a, b);
    }
}