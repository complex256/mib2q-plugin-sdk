package mibsdk.api;

/**
 * Which directions the parking sound plays in. Immutable.
 *
 * @since api 1
 */
public final class SoundDirections {

    private final boolean front;
    private final boolean rear;
    private final boolean left;
    private final boolean right;

    /**
     * @param front front tones
     * @param rear rear tones
     * @param left left tones
     * @param right right tones
     */
    public SoundDirections(boolean front, boolean rear, boolean left, boolean right) {
        this.front = front;
        this.rear = rear;
        this.left = left;
        this.right = right;
    }

    /** Whether front tones play. */
    public boolean front() {
        return front;
    }

    /** Whether rear tones play. */
    public boolean rear() {
        return rear;
    }

    /** Whether left tones play. */
    public boolean left() {
        return left;
    }

    /** Whether right tones play. */
    public boolean right() {
        return right;
    }

    /**
     * A copy with the rear direction changed.
     *
     * @param rear the new rear value
     * @return a new instance
     */
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
