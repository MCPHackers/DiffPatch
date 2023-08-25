package codechicken.diffpatch.util;

public class MutablePair<L, R> extends Pair<L, R> {
    public MutablePair() {
        super(null, null);
    }

    public MutablePair(L left, R right) {
        super(left, right);
    }

    public void setLeft(L left) {
        this.left = left;
    }

    public void setRight(R right) {
        this.right = right;
    }
}
