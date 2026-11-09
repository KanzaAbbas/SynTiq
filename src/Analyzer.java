import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Analyzer {

    public record SemError(int line, String message) {
    }

    // NEW: the signature of one method, stored in the method table
    private record MethodInfo(String name, String returnType, List<String> paramTypes, int line) {
    }

    // One entry of the symbol table
    public static class Symbol {
        public final String name;
        public final String type;
        public final String scope;
        public final int line;
        public final boolean isParam;
        public boolean used = false;

        Symbol(String name, String type, String scope, int line, boolean isParam) {
            this.name = name;
            this.type = type;
            this.scope = scope;
            this.line = line;
            this.isParam = isParam;
        }
    }

    // One level of nesting: a method, a block, or a cycle header
    private static class Scope {
        final String label;
        final Map<String, Symbol> symbols = new LinkedHashMap<>();

        Scope(String label) {
            this.label = label;
        }
    }

    private final Node root;
    private final List<SemError> errors = new ArrayList<>();
    private final List<SemError> warnings = new ArrayList<>();
    private final List<Symbol> allSymbols = new ArrayList<>();
    private final Deque<Scope> scopes = new ArrayDeque<>(); // first element = innermost scope
    private final Map<String, MethodInfo> methods = new LinkedHashMap<>(); // NEW: the method table
    private int loopDepth = 0;
    private String currentReturn = "empty";

    public Analyzer(Node root) {
        this.root = root;
    }

    public List<SemError> getErrors() {
        return errors;
    }

    public List<SemError> getWarnings() {
        return warnings;
    }

    public List<Symbol> getSymbols() {
        return allSymbols;
    }

    public void analyze() {
        for (Node blueprint : root.children) {
            blueprint(blueprint);
        }
        errors.sort(Comparator.comparingInt(SemError::line));
        warnings.sort(Comparator.comparingInt(SemError::line));
    }

    // =====================================================================
    //  Blueprint and methods
    // =====================================================================
    private void blueprint(Node bp) {
        boolean hasStart = false;

        // NEW: pass 1 - record every method's signature before checking any body,
        // so a method can call another one that is written later in the file
        for (Node m : bp.children) {
            MethodInfo info = methodInfo(m);
            if (methods.containsKey(info.name())) {
                error(m.line, "Method '" + info.name() + "' is already defined in blueprint '" + bp.value + "'");
            } else {
                methods.put(info.name(), info);
            }
            if (info.name().equals("start")) {
                hasStart = true;
            }
        }

        // pass 2 - check each method body
        for (Node m : bp.children) {
            method(m);
        }

        if (!hasStart) {
            error(bp.line, "Blueprint '" + bp.value + "' has no 'start' method (the program entry point)");
        }
    }

    // NEW: reads a method's name, return type and parameter types from the tree
    private MethodInfo methodInfo(Node m) {
        String name = methodName(m);
        String returnType = "empty";
        List<String> paramTypes = new ArrayList<>();

        for (Node c : m.children) {
            if (c.type.equals("Type")) {
                returnType = c.value;
            } else if (c.type.equals("Parameters")) {
                for (Node p : c.children) {
                    paramTypes.add(p.children.get(0).value);
                }
            }
        }
        return new MethodInfo(name, returnType, paramTypes, m.line);
    }

    private String methodName(Node m) {
        for (Node c : m.children) {
            if (c.type.equals("Name")) {
                return c.value;
            }
        }
        return "?";
    }

    private void method(Node m) {
        String name = methodName(m);
        String returnType = "empty";
        Node params = null;
        Node body = null;

        for (Node c : m.children) {
            if (c.type.equals("Type")) {
                returnType = c.value;
            } else if (c.type.equals("Parameters")) {
                params = c;
            } else if (c.type.equals("Body")) {
                body = c;
            }
        }

        currentReturn = returnType;
        loopDepth = 0;
        pushScope("method " + name + "()");

        if (params != null) {
            for (Node p : params.children) {
                String ptype = p.children.get(0).value;
                String pname = p.children.get(1).value;
                if (ptype.equals("empty")) {
                    error(p.line, "Parameter '" + pname + "' cannot have type 'empty'");
                }
                declare(pname, ptype, p.line, true);
            }
        }

        if (body != null) {
            for (Node st : body.children) {
                statement(st);
            }
            if (!returnType.equals("empty") && !alwaysReturns(body.children)) {
                error(m.line, "Method '" + name + "' returns " + returnType
                        + ", so every path must end with 'giveback'");
            }
        }
        popScope();
    }

    // Does every path through these statements end in 'giveback'?
    private boolean alwaysReturns(List<Node> statements) {
        for (Node s : statements) {
            if (returns(s)) {
                return true;
            }
        }
        return false;
    }

    private boolean returns(Node s) {
        switch (s.type) {
            case "Return":
                return true;
            case "Block":
                return alwaysReturns(s.children);
            case "If": {
                Node thenNode = null;
                Node elseNode = null;
                for (Node c : s.children) {
                    if (c.type.equals("Then")) {
                        thenNode = c;
                    } else if (c.type.equals("Else")) {
                        elseNode = c;
                    }
                }
                return thenNode != null && elseNode != null
                        && alwaysReturns(thenNode.children) && alwaysReturns(elseNode.children);
            }
            default:
                return false;
        }
    }

    // =====================================================================
    //  Statements
    // =====================================================================
    private void statement(Node s) {
        switch (s.type) {
            case "Declaration" -> declaration(s);
            case "Assignment" -> assignment(s);
            case "Show" -> typeOf(s.children.get(0));
            case "CallStmt" -> callType(s.children.get(0)); // NEW: result may be thrown away
            case "If" -> ifStatement(s);
            case "While" -> whileStatement(s);
            case "For" -> forStatement(s);
            case "Skip" -> {
                if (loopDepth == 0) {
                    error(s.line, "'skip' can only be used inside a loop (repeat or cycle)");
                }
            }
            case "Return" -> returnStatement(s);
            case "Block" -> scopedBlock(s, "block (line " + s.line + ")");
            default -> {
                // Stop needs no check: it may be used anywhere
            }
        }
    }

    private void scopedBlock(Node block, String label) {
        pushScope(label);
        for (Node st : block.children) {
            statement(st);
        }
        popScope();
    }

    private void declaration(Node d) {
        String declared = d.children.get(0).value;
        if (declared.equals("empty")) {
            error(d.line, "Variable '" + d.value + "' cannot have type 'empty'");
        }
        if (d.children.size() > 1) {
            String valueType = typeOf(d.children.get(1));
            checkAssignable(declared, valueType, d.line, "'" + d.value + "'");
        }
        // Declared after its initialiser is checked, so 'whole x := x;' is caught
        declare(d.value, declared, d.line, false);
    }

    private void assignment(Node a) {
        String valueType = typeOf(a.children.get(0));
        Symbol s = lookup(a.value);
        if (s == null) {
            error(a.line, "Variable '" + a.value + "' is assigned but never declared");
            return;
        }
        checkAssignable(s.type, valueType, a.line, "'" + a.value + "'");
    }

    private void ifStatement(Node s) {
        for (Node c : s.children) {
            switch (c.type) {
                case "Condition" -> requireTruth(c, "check");
                case "Then" -> scopedBlock(c, "check block (line " + c.line + ")");
                case "Else" -> scopedBlock(c, "otherwise block (line " + c.line + ")");
                default -> {
                }
            }
        }
    }

    private void whileStatement(Node s) {
        for (Node c : s.children) {
            if (c.type.equals("Condition")) {
                requireTruth(c, "repeat");
            } else if (c.type.equals("Body")) {
                loopDepth++;
                scopedBlock(c, "repeat block (line " + c.line + ")");
                loopDepth--;
            }
        }
    }

    private void forStatement(Node s) {
        pushScope("cycle (line " + s.line + ")");
        for (Node c : s.children) {
            switch (c.type) {
                case "Init", "Update" -> statement(c.children.get(0));
                case "Condition" -> requireTruth(c, "cycle");
                case "Body" -> {
                    loopDepth++;
                    scopedBlock(c, "cycle block (line " + c.line + ")");
                    loopDepth--;
                }
                default -> {
                }
            }
        }
        popScope();
    }

    private void returnStatement(Node s) {
        boolean hasValue = !s.children.isEmpty();
        String valueType = hasValue ? typeOf(s.children.get(0)) : "none";

        if (currentReturn.equals("empty")) {
            if (hasValue) {
                error(s.line, "This method returns 'empty', so 'giveback' cannot carry a value");
            }
        } else if (!hasValue) {
            error(s.line, "'giveback' needs a value of type " + currentReturn);
        } else {
            checkAssignable(currentReturn, valueType, s.line, "'giveback'");
        }
    }

    private void requireTruth(Node condition, String keyword) {
        String t = typeOf(condition.children.get(0));
        if (!t.equals("truth") && !t.equals("error")) {
            error(condition.line, "The condition of '" + keyword
                    + "' must be a truth (true/false) expression, but it is " + t);
        }
    }

    // =====================================================================
    //  Expressions: every method returns the type of the expression.
    //  Types: whole, decimal, text, letter, truth, nil, or "error" when an
    //  error was already reported (so one mistake is not reported twice).
    // =====================================================================
    private String typeOf(Node e) {
        switch (e.type) {
            case "IntLiteral":
                return "whole";
            case "DecimalLiteral":
                return "decimal";
            case "StringLiteral":
                return "text";
            case "CharLiteral":
                return "letter";
            case "BoolLiteral":
                return "truth";
            case "NilLiteral":
                return "nil";
            case "Identifier": {
                Symbol s = lookup(e.value);
                if (s == null) {
                    error(e.line, "Variable '" + e.value + "' is used but never declared");
                    return "error";
                }
                s.used = true;
                return category(s.type);
            }
            case "Call": { // NEW: a method call used as a value
                String t = callType(e);
                if (t.equals("empty")) {
                    error(e.line, "Method '" + e.value + "' returns 'empty', so it cannot be used as a value");
                    return "error";
                }
                return t;
            }
            case "UnaryOp":
                return unaryType(e);
            case "BinaryOp":
                return binaryType(e);
            default:
                return "error";
        }
    }

    // NEW: checks one call and returns the type of what the method gives back
    // (which can be "empty" for a method with no result)
    private String callType(Node call) {
        MethodInfo info = methods.get(call.value);

        // Always check the arguments first, so variables inside them count as used
        // and mistakes inside them are reported
        List<String> argTypes = new ArrayList<>();
        for (Node arg : call.children) {
            argTypes.add(typeOf(arg));
        }

        if (info == null) {
            error(call.line, "Method '" + call.value + "' is not defined");
            return "error";
        }

        if (argTypes.size() != info.paramTypes().size()) {
            error(call.line, "Method '" + call.value + "' expects " + info.paramTypes().size()
                    + " argument(s) but got " + argTypes.size());
            return category(info.returnType());
        }

        for (int i = 0; i < argTypes.size(); i++) {
            checkAssignable(info.paramTypes().get(i), argTypes.get(i), call.line,
                    "argument " + (i + 1) + " of '" + call.value + "()'");
        }
        return category(info.returnType());
    }

    private String unaryType(Node e) {
        String t = typeOf(e.children.get(0));
        if (t.equals("error")) {
            return "error";
        }
        if (e.value.equals("negate")) {
            if (!t.equals("truth")) {
                error(e.line, "'negate' needs a truth value, but got " + t);
                return "error";
            }
            return "truth";
        }
        // unary ~-
        if (!isNumeric(t)) {
            error(e.line, "Unary '~-' needs a number, but got " + t);
            return "error";
        }
        return t;
    }

    private String binaryType(Node e) {
        String op = e.value;
        Node leftNode = e.children.get(0);
        Node rightNode = e.children.get(1);
        String l = typeOf(leftNode);
        String r = typeOf(rightNode);

        if (l.equals("error") || r.equals("error")) {
            return "error";
        }

        switch (op) {
            case "~+", "~-", "~*", "~/", "~%": {
                // '~+' also joins text, e.g. "Score: " ~+ score
                if (op.equals("~+") && (l.equals("text") || r.equals("text"))) {
                    return "text";
                }
                if (!isNumeric(l) || !isNumeric(r)) {
                    error(e.line, "Operator '" + op + "' needs two numbers, but got " + l + " and " + r);
                    return "error";
                }
                if ((op.equals("~/") || op.equals("~%")) && isZero(rightNode)) {
                    error(e.line, "Division by zero: the right side of '" + op + "' is 0");
                    return "error";
                }
                return (l.equals("decimal") || r.equals("decimal")) ? "decimal" : "whole";
            }
            case "~>", "~<": {
                boolean ok = (isNumeric(l) && isNumeric(r)) || (l.equals("letter") && r.equals("letter"));
                if (!ok) {
                    error(e.line, "Operator '" + op + "' compares numbers (or letters), but got " + l + " and " + r);
                    return "error";
                }
                return "truth";
            }
            case "~=", "~!": {
                boolean ok = (isNumeric(l) && isNumeric(r)) || l.equals(r)
                        || (l.equals("nil") && r.equals("text")) || (l.equals("text") && r.equals("nil"));
                if (!ok) {
                    error(e.line, "Operator '" + op + "' cannot compare " + l + " with " + r);
                    return "error";
                }
                return "truth";
            }
            case "both", "either": {
                if (!l.equals("truth") || !r.equals("truth")) {
                    error(e.line, "Operator '" + op + "' needs two truth values, but got " + l + " and " + r);
                    return "error";
                }
                return "truth";
            }
            default:
                return "error";
        }
    }

    private boolean isZero(Node n) {
        if (n.type.equals("IntLiteral") || n.type.equals("DecimalLiteral")) {
            return Double.parseDouble(n.value) == 0.0;
        }
        return false;
    }

    private boolean isNumeric(String t) {
        return t.equals("whole") || t.equals("decimal");
    }

    // Maps the many declared types onto the type families used for checking
    private String category(String type) {
        switch (type) {
            case "whole", "bigwhole", "smallwhole", "tinywhole":
                return "whole";
            case "decimal", "shortdecimal":
                return "decimal";
            case "text":
                return "text";
            case "letter":
                return "letter";
            case "truth":
                return "truth";
            case "empty":
                return "empty";
            default:
                return "error";
        }
    }

    // Can a value of this type be stored in something of the target type?
    private void checkAssignable(String targetType, String valueType, int line, String subject) {
        String target = category(targetType);
        if (valueType.equals("error") || target.equals("empty") || target.equals("error")) {
            return;
        }
        boolean ok = switch (target) {
            case "whole" -> valueType.equals("whole");
            case "decimal" -> valueType.equals("whole") || valueType.equals("decimal");
            case "text" -> valueType.equals("text") || valueType.equals("nil");
            case "letter" -> valueType.equals("letter");
            case "truth" -> valueType.equals("truth");
            default -> false;
        };
        if (!ok) {
            String hint = (target.equals("whole") && valueType.equals("decimal"))
                    ? " (a decimal would lose its fraction)" : "";
            error(line, "Type mismatch: " + subject + " expects " + targetType + " but got " + valueType + hint);
        }
    }

    // =====================================================================
    //  Symbol table helpers
    // =====================================================================
    private void pushScope(String label) {
        scopes.push(new Scope(label));
    }

    private void popScope() {
        Scope s = scopes.pop();
        for (Symbol sym : s.symbols.values()) {
            if (!sym.used && !sym.isParam) {
                warnings.add(new SemError(sym.line, "Variable '" + sym.name + "' is declared but never used"));
            }
        }
    }

    private Symbol lookup(String name) {
        for (Scope s : scopes) {
            Symbol sym = s.symbols.get(name);
            if (sym != null) {
                return sym;
            }
        }
        return null;
    }

    private void declare(String name, String type, int line, boolean isParam) {
        Symbol existing = lookup(name);
        if (existing != null) {
            error(line, "Variable '" + name + "' is already declared (line " + existing.line + ")");
            return;
        }
        Scope current = scopes.peek();
        Symbol sym = new Symbol(name, type, current.label, line, isParam);
        current.symbols.put(name, sym);
        allSymbols.add(sym);
    }

    private void error(int line, String message) {
        errors.add(new SemError(line, message));
    }
}