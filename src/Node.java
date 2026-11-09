import java.util.ArrayList;
import java.util.List;

public class Node {

    public final String type;
    public final String value;
    public final int line;
    public final List<Node> children = new ArrayList<>();

    public Node(String type, String value, int line) {
        this.type = type;
        this.value = value;
        this.line = line;
    }

    public Node add(Node child) {
        children.add(child);
        return this;
    }

    public String label() {
        return value.isEmpty() ? type : type + " : " + value;
    }

    // Draws the tree as plain text, e.g.
    // Program
    // `-- Blueprint : Main
    //     |-- Method
    public String toTree() {
        StringBuilder sb = new StringBuilder();
        sb.append(label()).append("\n");
        for (int i = 0; i < children.size(); i++) {
            children.get(i).print(sb, "", i == children.size() - 1);
        }
        return sb.toString();
    }

    private void print(StringBuilder sb, String prefix, boolean last) {
        sb.append(prefix).append(last ? "`-- " : "|-- ").append(label()).append("\n");
        String next = prefix + (last ? "    " : "|   ");
        for (int i = 0; i < children.size(); i++) {
            children.get(i).print(sb, next, i == children.size() - 1);
        }
    }
}