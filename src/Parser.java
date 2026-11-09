import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class Parser {

    public record SyntaxError(int line, int column, String message) {
    }

    private static class ParseException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ParseException(String message) {
            super(message);
        }
    }

    private static final Set<String> TYPES = Set.of(
            "whole", "bigwhole", "smallwhole", "tinywhole",
            "decimal", "shortdecimal", "letter", "truth", "text", "empty");

    private static final Set<String> MODIFIERS = Set.of(
            "open", "hidden", "guarded", "shared", "locked");

    private static final Set<String> STATEMENT_STARTERS = Set.of(
            "check", "repeat", "cycle", "stop", "skip", "giveback", "show");

    private final List<Token> tokens;
    private int current = 0;
    private final List<SyntaxError> errors = new ArrayList<>();

    public Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    public List<SyntaxError> getErrors() {
        return errors;
    }

    // =====================================================================
    //  program -> blueprint
    // =====================================================================
    public Node parse() {
        Node root = new Node("Program", "", 1);
        try {
            root.add(blueprint());
            if (!isAtEnd()) {
                throw error(peek(), "Unexpected " + describe(peek()) + " after the end of the blueprint");
            }
        } catch (ParseException e) {
            // the error was already recorded
        }
        return root;
    }

    // blueprint -> "blueprint" IDENT "{" { method } "}"
    private Node blueprint() {
        Token kw = expect("blueprint", "at the start of the program");
        Token name = expectIdentifier("after 'blueprint'");
        Node node = new Node("Blueprint", name.lexeme(), kw.line());
        expect("{", "after the blueprint name");

        while (!check("}") && !isAtEnd()) {
            int before = current;
            try {
                node.add(method());
            } catch (ParseException e) {
                synchronizeMember();
                if (current == before) {
                    advance();
                }
            }
        }
        expect("}", "to close the blueprint");
        return node;
    }

    // method -> { modifier } type (IDENT | "start") "(" [ params ] ")" block
    private Node method() {
        Node node = new Node("Method", "", peek().line());

        Node mods = new Node("Modifiers", "", peek().line());
        while (peek().type() == TokenType.KEYWORD && MODIFIERS.contains(peek().lexeme())) {
            Token m = advance();
            mods.add(new Node("Modifier", m.lexeme(), m.line()));
        }
        if (!mods.children.isEmpty()) {
            node.add(mods);
        }

        node.add(typeNode("as the return type of the method"));

        Token name;
        if (check("start")) {
            name = advance();
        } else {
            name = expectIdentifier("as the method name");
        }
        node.add(new Node("Name", name.lexeme(), name.line()));

        expect("(", "after the method name");
        Node params = new Node("Parameters", "", peek().line());
        if (!check(")")) {
            do {
                Node p = new Node("Parameter", "", peek().line());
                p.add(typeNode("for the parameter"));
                Token pn = expectIdentifier("as the parameter name");
                p.add(new Node("Name", pn.lexeme(), pn.line()));
                params.add(p);
            } while (match(","));
        }
        expectSoft(")", "after the parameters");
        node.add(params);

        node.add(block("Body"));
        return node;
    }

    // =====================================================================
    //  Statements
    // =====================================================================

    // block -> "{" { statement } "}"
    private Node block(String label) {
        Token open = expect("{", "to start a block");
        Node block = new Node(label, "", open.line());

        while (!check("}") && !isAtEnd()) {
            int before = current;
            try {
                block.add(statement());
            } catch (ParseException e) {
                synchronizeStatement();
                if (current == before) {
                    advance();
                }
            }
        }
        expect("}", "to close the block");
        return block;
    }

    private Node statement() {
        Token t = peek();

        if (isType()) {
            Node d = declaration();
            expectSoft(";", "after the declaration");
            return d;
        }
        // NEW: a name followed by '(' is a method call used as a statement
        if (t.type() == TokenType.IDENTIFIER && nextIs("(")) {
            Node stmt = new Node("CallStmt", "", t.line());
            stmt.add(call());
            expectSoft(";", "after the method call");
            return stmt;
        }
        if (t.type() == TokenType.IDENTIFIER) {
            Node a = assignment();
            expectSoft(";", "after the assignment");
            return a;
        }
        if (check("show")) {
            return showStatement();
        }
        if (check("check")) {
            return ifStatement();
        }
        if (check("repeat")) {
            return whileStatement();
        }
        if (check("cycle")) {
            return forStatement();
        }
        if (check("stop")) {
            advance();
            expectSoft(";", "after 'stop'");
            return new Node("Stop", "", t.line());
        }
        if (check("skip")) {
            advance();
            expectSoft(";", "after 'skip'");
            return new Node("Skip", "", t.line());
        }
        if (check("giveback")) {
            advance();
            Node ret = new Node("Return", "", t.line());
            if (!check(";")) {
                ret.add(expression());
            }
            expectSoft(";", "after 'giveback'");
            return ret;
        }
        if (check("{")) {
            return block("Block");
        }
        if (check("otherwise")) {
            advance();
            ParseException problem = error(t, "'otherwise' has no matching 'check' before it");
            if (check("{")) {
                block("Else"); // parse and discard it so the braces stay balanced
            }
            throw problem;
        }
        throw error(t, "Unexpected " + describe(t) + " at the start of a statement");
    }

    // declaration -> type IDENT [ ":=" expression ]
    private Node declaration() {
        Node typeN = typeNode("at the start of a declaration");
        Token name = expectIdentifier("as the variable name");
        Node d = new Node("Declaration", name.lexeme(), name.line());
        d.add(typeN);
        if (match(":=")) {
            d.add(expression());
        }
        return d;
    }

    // assignment -> IDENT ":=" expression
    private Node assignment() {
        Token name = advance();
        Node a = new Node("Assignment", name.lexeme(), name.line());
        expect(":=", "after the variable name (assignment uses ':=')");
        a.add(expression());
        return a;
    }

    // showStmt -> "show" "(" expression ")" ";"
    private Node showStatement() {
        Token kw = advance();
        Node n = new Node("Show", "", kw.line());
        expect("(", "after 'show'");
        n.add(expression());
        expectSoft(")", "after the value to show");
        expectSoft(";", "after show(...)");
        return n;
    }

    // ifStmt -> "check" "(" expression ")" block [ "otherwise" ( ifStmt | block ) ]
    private Node ifStatement() {
        Token kw = advance();
        Node node = new Node("If", "", kw.line());
        expect("(", "after 'check'");
        Node cond = new Node("Condition", "", kw.line());
        cond.add(expression());
        expectSoft(")", "after the condition");
        node.add(cond);
        node.add(block("Then"));

        if (match("otherwise")) {
            if (check("check")) {
                Node elseNode = new Node("Else", "", peek().line());
                elseNode.add(ifStatement());
                node.add(elseNode);
            } else {
                node.add(block("Else"));
            }
        }
        return node;
    }

    // whileStmt -> "repeat" "(" expression ")" block
    private Node whileStatement() {
        Token kw = advance();
        Node node = new Node("While", "", kw.line());
        expect("(", "after 'repeat'");
        Node cond = new Node("Condition", "", kw.line());
        cond.add(expression());
        expectSoft(")", "after the condition");
        node.add(cond);
        node.add(block("Body"));
        return node;
    }

    // forStmt -> "cycle" "(" (declaration | assignment) ";" expression ";" assignment ")" block
    private Node forStatement() {
        Token kw = advance();
        Node node = new Node("For", "", kw.line());
        expect("(", "after 'cycle'");

        Node init = new Node("Init", "", peek().line());
        if (isType()) {
            init.add(declaration());
        } else if (peek().type() == TokenType.IDENTIFIER) {
            init.add(assignment());
        } else {
            throw error(peek(), "Expected a declaration or assignment to start the cycle but found "
                    + describe(peek()));
        }
        expectSoft(";", "after the cycle initialiser");
        node.add(init);

        Node cond = new Node("Condition", "", peek().line());
        cond.add(expression());
        expectSoft(";", "after the cycle condition");
        node.add(cond);

        Node update = new Node("Update", "", peek().line());
        if (peek().type() != TokenType.IDENTIFIER) {
            throw error(peek(), "Expected an assignment as the cycle update (for example i := i ~+ 1) but found "
                    + describe(peek()));
        }
        update.add(assignment());
        expectSoft(")", "after the cycle header");
        node.add(update);

        node.add(block("Body"));
        return node;
    }

    // =====================================================================
    //  Expressions (lowest precedence first)
    //    either  <  both  <  ~= ~!  <  ~> ~<  <  ~+ ~-  <  ~* ~/ ~%  <  unary
    // =====================================================================
    private Node expression() {
        return orExpr();
    }

    private Node orExpr() {
        Node left = andExpr();
        while (check("either")) {
            Token op = advance();
            left = binary(op, left, andExpr());
        }
        return left;
    }

    private Node andExpr() {
        Node left = equality();
        while (check("both")) {
            Token op = advance();
            left = binary(op, left, equality());
        }
        return left;
    }

    private Node equality() {
        Node left = relational();
        while (check("~=") || check("~!")) {
            Token op = advance();
            left = binary(op, left, relational());
        }
        return left;
    }

    private Node relational() {
        Node left = additive();
        while (check("~>") || check("~<")) {
            Token op = advance();
            left = binary(op, left, additive());
        }
        return left;
    }

    private Node additive() {
        Node left = term();
        while (check("~+") || check("~-")) {
            Token op = advance();
            left = binary(op, left, term());
        }
        return left;
    }

    private Node term() {
        Node left = unary();
        while (check("~*") || check("~/") || check("~%")) {
            Token op = advance();
            left = binary(op, left, unary());
        }
        return left;
    }

    private Node unary() {
        if (check("negate") || check("~-")) {
            Token op = advance();
            Node n = new Node("UnaryOp", op.lexeme(), op.line());
            n.add(unary());
            return n;
        }
        return primary();
    }

    private Node primary() {
        Token t = peek();
        switch (t.type()) {
            case INTEGER_LITERAL -> {
                advance();
                return new Node("IntLiteral", t.lexeme(), t.line());
            }
            case DECIMAL_LITERAL -> {
                advance();
                return new Node("DecimalLiteral", t.lexeme(), t.line());
            }
            case STRING_LITERAL -> {
                advance();
                return new Node("StringLiteral", t.lexeme(), t.line());
            }
            case CHAR_LITERAL -> {
                advance();
                return new Node("CharLiteral", t.lexeme(), t.line());
            }
            case BOOLEAN_LITERAL -> {
                advance();
                return new Node("BoolLiteral", t.lexeme(), t.line());
            }
            case NIL_LITERAL -> {
                advance();
                return new Node("NilLiteral", t.lexeme(), t.line());
            }
            case IDENTIFIER -> {
                // NEW: IDENT followed by '(' is a method call, otherwise a variable
                if (nextIs("(")) {
                    return call();
                }
                advance();
                return new Node("Identifier", t.lexeme(), t.line());
            }
            default -> {
            }
        }
        if (check("(")) {
            advance();
            Node e = expression();
            expectSoft(")", "to close the parenthesised expression");
            return e;
        }
        throw error(t, "Expected a value or expression but found " + describe(t));
    }

    // NEW: call -> IDENT "(" [ expression { "," expression } ] ")"
    private Node call() {
        Token name = advance();
        Node n = new Node("Call", name.lexeme(), name.line());
        expect("(", "after the method name");
        if (!check(")")) {
            do {
                n.add(expression());
            } while (match(","));
        }
        expectSoft(")", "after the arguments");
        return n;
    }

    private Node binary(Token op, Node left, Node right) {
        Node n = new Node("BinaryOp", op.lexeme(), op.line());
        n.add(left);
        n.add(right);
        return n;
    }

    // =====================================================================
    //  Helpers
    // =====================================================================
    private boolean isType() {
        return peek().type() == TokenType.KEYWORD && TYPES.contains(peek().lexeme());
    }

    private Node typeNode(String context) {
        if (!isType()) {
            throw error(peek(), "Expected a data type " + context
                    + " (whole, decimal, text, truth, ...) but found " + describe(peek()));
        }
        Token t = advance();
        return new Node("Type", t.lexeme(), t.line());
    }

    private Token expectIdentifier(String context) {
        Token t = peek();
        if (t.type() == TokenType.IDENTIFIER) {
            return advance();
        }
        if (t.type() == TokenType.KEYWORD) {
            advance(); // skip the reserved word so recovery moves past it
            throw error(t, "'" + t.lexeme() + "' is a reserved SYNTIQ word and cannot be used " + context);
        }
        throw error(t, "Expected a name " + context + " but found " + describe(t));
    }

    private Token expect(String lexeme, String context) {
        if (check(lexeme)) {
            return advance();
        }
        throw error(peek(), "Expected '" + lexeme + "' " + context + " but found " + describe(peek()));
    }

    // For a missing ';' or ')': record the error (pointing just after the previous
    // token), then carry on as if the token had been there. This keeps one slip
    // from causing a chain of follow-on errors.
    private void expectSoft(String lexeme, String context) {
        if (check(lexeme)) {
            advance();
            return;
        }
        Token prev = current > 0 ? tokens.get(current - 1) : peek();
        errorAt(prev.line(), prev.column() + prev.lexeme().length(),
                "Expected '" + lexeme + "' " + context + " but found " + describe(peek()));
    }

    private boolean check(String lexeme) {
        Token t = peek();
        return t.type() != TokenType.EOF && t.lexeme().equals(lexeme);
    }

    // NEW: look one token ahead without consuming anything
    private boolean nextIs(String lexeme) {
        if (current + 1 >= tokens.size()) {
            return false;
        }
        Token n = tokens.get(current + 1);
        return n.type() != TokenType.EOF && n.lexeme().equals(lexeme);
    }

    private boolean match(String lexeme) {
        if (check(lexeme)) {
            advance();
            return true;
        }
        return false;
    }

    private Token peek() {
        return tokens.get(current);
    }

    private Token advance() {
        Token t = tokens.get(current);
        if (!isAtEnd()) {
            current++;
        }
        return t;
    }

    private boolean isAtEnd() {
        return peek().type() == TokenType.EOF;
    }

    private String describe(Token t) {
        return t.type() == TokenType.EOF ? "end of input" : "'" + t.lexeme() + "'";
    }

    private ParseException error(Token t, String message) {
        return errorAt(t.line(), t.column(), message);
    }

    private ParseException errorAt(int line, int column, String message) {
        // Only one error per position, so recovery does not repeat itself
        boolean duplicate = !errors.isEmpty()
                && errors.get(errors.size() - 1).line() == line
                && errors.get(errors.size() - 1).column() == column;
        if (!duplicate) {
            errors.add(new SyntaxError(line, column, message));
        }
        return new ParseException(message);
    }

    // ---------- Panic-mode error recovery ----------
    // After an error inside a block: skip to just after the next ';',
    // or stop in front of '}' or a keyword that starts a new statement.
    private void synchronizeStatement() {
        while (!isAtEnd()) {
            if (check(";")) {
                advance();
                return;
            }
            if (check("}")) {
                return;
            }
            Token t = peek();
            if (t.type() == TokenType.KEYWORD
                    && (TYPES.contains(t.lexeme()) || STATEMENT_STARTERS.contains(t.lexeme()))) {
                return;
            }
            advance();
        }
    }

    // After an error in a method signature: skip the whole method body
    private void synchronizeMember() {
        int depth = 0;
        while (!isAtEnd()) {
            if (check("{")) {
                depth++;
            } else if (check("}")) {
                if (depth == 0) {
                    return; // this '}' closes the blueprint
                }
                depth--;
                if (depth == 0) {
                    advance();
                    return;
                }
            }
            advance();
        }
    }
}