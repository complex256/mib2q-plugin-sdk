package mibsdk.api;

/** Which directions the parking sound plays in. Immutable. */
public final class SoundDirections {

    private final boolean front;
    private final boolean rear;
    private final boolean left;
    private final boolean right;

    public SoundDirections(boolean front, boolean rear, boolean left, boolean right) {
        this.front = front;
        this.rear = rear;
        this.left = left;
        this.right = right;
    }

    public boolean front() {
        return front;
    }

    public boolean rear() {
        return rear;
    }

    public boolean left() {
        return left;
    }

    public boolean right() {
        return right;
    }

    public SoundDirections withRear(boolean rear) {
        return new SoundDirections(front, rear, left, right);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SoundDirections)) {
            return false;
        }
        SoundDirections other = (SoundDirections) o;
        return front == other.front && rear == other.rear && left == other.left && right == other.right;
    }

    @Override
    public int hashCode() {
        int h = front ? 1 : 0;
        h = 31 * h + (rear ? 1 : 0);
        h = 31 * h + (left ? 1 : 0);
        h = 31 * h + (right ? 1 : 0);
        return h;
    }

    @Override
    public String toString() {
        return "SoundDirections{front=" + front + ", rear=" + rear + ", left=" + left + ", right=" + right + "}";
    }
}
