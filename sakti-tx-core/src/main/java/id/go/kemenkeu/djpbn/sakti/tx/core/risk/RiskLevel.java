package id.go.kemenkeu.djpbn.sakti.tx.core.risk;

/**
 * Risk severity levels
 */
public enum RiskLevel {
    
    LOW(0),
    MEDIUM(5),
    HIGH(10),
    CRITICAL(20);
    
    private final int score;
    
    RiskLevel(int score) {
        this.score = score;
    }
    
    public int getScore() {
        return score;
    }
    
    public boolean isHigherThan(RiskLevel other) {
        return this.score > other.score;
    }
}