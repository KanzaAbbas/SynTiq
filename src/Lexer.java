import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Lexer {

    public record LexError(int line, int column, String message) {
    }

    // ---------- Keyword table (from the SYNTIQ proposal) ----------
    private static final Map<String, TokenType> WORDS = new HashMap<>();

    static {
        String[] keywords = {
            // data types
            "whole", "bigwhole", "smallwhole", "tinywhole", "decimal", "shortdecimal",
            "letter", "truth", "text", "empty",
            // decisions and loops
            "check", "otherwise", "compare", "option", "fallback", "repeat", "cycle",
            "inside", "perform", "stop", "skip", "giveback", "send",
            // classes and objects
            "blueprint", "contract", "choices", "incomplete", "inherits", "follows",
            "create", "me", "parent", "kindof", "bundle", "bring", "start",
            // access and modifiers
            "open", "hidden", "guarded", "shared", "locked", "parallel",
            // errors, input and output
            "attempt", "catch", "always", "throw", "raises", "verify", "show", "ask",
            // lambda
            "quick"
        };
        for (String k : keywords) {
            WORDS.put(k, TokenType.KEYWORD);
        }
        WORDS.put("both", TokenType.LOGICAL_OPERATOR);
        WORDS.put("either", TokenType.LOGICAL_OPERATOR);
        WORDS.put("negate", TokenType.LOGICAL_OPERATOR);
        WORDS.put("valid", TokenType.BOOLEAN_LITERAL);
        WORDS.put("invalid", TokenType.BOOLEAN_LITERAL);
        WORDS.put("nil", TokenType.NIL_LITERAL);
    }

    private static final String DELIMITERS = "(){}[];,.";
    private static final String TILDE_OPS = "+-*/%><=!";

    // ---------- State ----------
    private final String source;
    private int pos = 0;
    private int line = 1;
    private int col = 1;
    private final List<Token> tokens = new ArrayList<>();
    private final List<LexError> errors = new ArrayList<>();

    public Lexer(String source) {
        this.source = source;
    }

    public List<LexError> getErrors() {
        return errors;
    }

    // ---------- Main scanning loop ----------
    public List<Token> tokenize() {
        while (!atEnd()) {
            char c = peek();

            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                advance();
            } else if (c == '#' && peekNext() == '#') {
                skipComment();
            } else if (isAlpha(c)) {
                readWord();
            } else if (isDigit(c)) {
                readNumber();
            } else if (c == '"') {
                readString();
            } else if (c == '\'') {
                readChar();
            } else if (c == '~') {
                readTildeOperator();
            } else if (c == ':') {
                readColon();
            } else if (c == '=') {
                readEquals();
            } else if (DELIMITERS.indexOf(c) >= 0) {
                int l = line, c0 = col;
                advance();
                add(TokenType.DELIMITER, String.valueOf(c), l, c0);
            } else {
                int l = line, c0 = col;
                char bad = advance();
                String hint = (bad == '#') ? " (comments start with two hash signs: ##)" : "";
                error(l, c0, "Unexpected character '" + bad + "'" + hint);
                add(TokenType.ERROR, String.valueOf(bad), l, c0);
            }
        }
        tokens.add(new Token(TokenType.EOF, "", line, col));
        return tokens;
    }

    // ---------- Scanners for each token class ----------
    private void skipComment() {
        while (!atEnd() && peek() != '\n') {
            advance();
        }
    }

    private void readWord() {
        int start = pos, l = line, c0 = col;
        while (!atEnd() && (isAlpha(peek()) || isDigit(peek()))) {
            advance();
        }
        String word = source.substring(start, pos);
        add(WORDS.getOrDefault(word, TokenType.IDENTIFIER), word, l, c0);
    }

    private void readNumber() {
        int start = pos, l = line, c0 = col;
        boolean isDecimal = false;

        while (!atEnd() && isDigit(peek())) {
            advance();
        }
        if (peek() == '.' && isDigit(peekNext())) {
            isDecimal = true;
            advance(); // the dot
            while (!atEnd() && isDigit(peek())) {
                advance();
            }
        }
        // A number glued to letters, like 9lives
        if (!atEnd() && isAlpha(peek())) {
            while (!atEnd() && (isAlpha(peek()) || isDigit(peek()))) {
                advance();
            }
            String bad = source.substring(start, pos);
            error(l, c0, "Invalid token '" + bad + "': identifiers cannot start with a digit");
            add(TokenType.ERROR, bad, l, c0);
            return;
        }
        String text = source.substring(start, pos);
        add(isDecimal ? TokenType.DECIMAL_LITERAL : TokenType.INTEGER_LITERAL, text, l, c0);
    }

    private void readString() {
        int start = pos, l = line, c0 = col;
        boolean closed = false;
        advance(); // opening quote

        while (!atEnd()) {
            char ch = peek();
            if (ch == '\n') {
                break;
            }
            if (ch == '\\') { // escape sequence like \" or \n
                advance();
                if (!atEnd() && peek() != '\n') {
                    advance();
                }
                continue;
            }
            if (ch == '"') {
                advance();
                closed = true;
                break;
            }
            advance();
        }

        String text = source.substring(start, pos);
        if (closed) {
            add(TokenType.STRING_LITERAL, text, l, c0);
        } else {
            error(l, c0, "Unterminated string literal");
            add(TokenType.ERROR, text, l, c0);
        }
    }

    private void readChar() {
        int start = pos, l = line, c0 = col;
        advance(); // opening quote

        if (peek() == '\'') {
            advance();
            error(l, c0, "Empty character literal");
            add(TokenType.ERROR, source.substring(start, pos), l, c0);
            return;
        }
        if (atEnd() || peek() == '\n') {
            error(l, c0, "Unterminated character literal");
            add(TokenType.ERROR, source.substring(start, pos), l, c0);
            return;
        }

        if (peek() == '\\') { // escape like '\n'
            advance();
            if (!atEnd() && peek() != '\n') {
                advance();
            }
        } else {
            advance();
        }

        if (peek() == '\'') {
            advance();
            add(TokenType.CHAR_LITERAL, source.substring(start, pos), l, c0);
        } else {
            while (!atEnd() && peek() != '\'' && peek() != '\n') {
                advance();
            }
            if (peek() == '\'') {
                advance();
            }
            error(l, c0, "Invalid character literal (must hold exactly one character)");
            add(TokenType.ERROR, source.substring(start, pos), l, c0);
        }
    }

    private void readTildeOperator() {
        int l = line, c0 = col;
        advance(); // the ~
        if (!atEnd() && TILDE_OPS.indexOf(peek()) >= 0) {
            char op = advance();
            add(TokenType.OPERATOR, "~" + op, l, c0);
        } else {
            error(l, c0, "'~' must be followed by one of + - * / % > < = !  (for example ~+)");
            add(TokenType.ERROR, "~", l, c0);
        }
    }

    private void readColon() {
        int l = line, c0 = col;
        if (peekNext() == '=') {
            advance();
            advance();
            add(TokenType.OPERATOR, ":=", l, c0);
        } else {
            advance();
            error(l, c0, "':' on its own is not valid. Did you mean ':=' (assignment)?");
            add(TokenType.ERROR, ":", l, c0);
        }
    }

    private void readEquals() {
        int l = line, c0 = col;
        if (peekNext() == '>') {
            advance();
            advance();
            add(TokenType.OPERATOR, "=>", l, c0);
        } else {
            advance();
            error(l, c0, "'=' on its own is not valid. Use ':=' for assignment or '~=' for comparison");
            add(TokenType.ERROR, "=", l, c0);
        }
    }

    // ---------- Helpers ----------
    private boolean atEnd() {
        return pos >= source.length();
    }

    private char peek() {
        return atEnd() ? '\0' : source.charAt(pos);
    }

    private char peekNext() {
        return (pos + 1 >= source.length()) ? '\0' : source.charAt(pos + 1);
    }

    private char advance() {
        char c = source.charAt(pos++);
        if (c == '\n') {
            line++;
            col = 1;
        } else {
            col++;
        }
        return c;
    }

    private boolean isAlpha(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private void add(TokenType type, String lexeme, int l, int c) {
        tokens.add(new Token(type, lexeme, l, c));
    }

    private void error(int l, int c, String message) {
        errors.add(new LexError(l, c, message));
    }
}