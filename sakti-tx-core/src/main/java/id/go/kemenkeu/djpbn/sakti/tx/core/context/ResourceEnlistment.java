package id.go.kemenkeu.djpbn.sakti.tx.core.context;

import java.time.Instant;
import java.util.Objects;

/**
 * Represents an enlisted resource (database, JMS, etc.)
 */
public class ResourceEnlistment {
    
    private final String name;           // e.g., "db1", "db2"
    private final String type;           // e.g., "DATABASE", "JMS"
    private final Instant enlistedAt;
    private boolean prepared;
    private boolean committed;
    
    public ResourceEnlistment(String name, String type) {
        this.name = name;
        this.type = type;
        this.enlistedAt = Instant.now();
        this.prepared = false;
        this.committed = false;
    }
    
    public String getName() {
        return name;
    }
    
    public String getType() {
        return type;
    }
    
    public Instant getEnlistedAt() {
        return enlistedAt;
    }
    
    public boolean isPrepared() {
        return prepared;
    }
    
    public void setPrepared(boolean prepared) {
        this.prepared = prepared;
    }
    
    public boolean isCommitted() {
        return committed;
    }
    
    public void setCommitted(boolean committed) {
        this.committed = committed;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ResourceEnlistment that = (ResourceEnlistment) o;
        return Objects.equals(name, that.name) && Objects.equals(type, that.type);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(name, type);
    }
    
    @Override
    public String toString() {
        return "ResourceEnlistment{" +
                "name='" + name + '\'' +
                ", type='" + type + '\'' +
                ", prepared=" + prepared +
                ", committed=" + committed +
                '}';
    }
}