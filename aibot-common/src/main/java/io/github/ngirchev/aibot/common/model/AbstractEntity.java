package io.github.ngirchev.aibot.common.model;

import lombok.Getter;
import lombok.Setter;

import jakarta.persistence.MappedSuperclass;
import java.io.Serializable;
import java.util.Objects;

/**
 * Base class for all entities with common equals and hashCode logic.
 * @param <ID> entity identifier type
 */
@MappedSuperclass
@Getter
@Setter
public abstract class AbstractEntity<ID extends Serializable> implements Serializable {
    
    /**
     * Gets entity identifier.
     * @return entity identifier
     */
    public abstract ID getId();
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AbstractEntity<?> that = (AbstractEntity<?>) o;
        return Objects.equals(getId(), that.getId());
    }
    
    @Override
    public int hashCode() {
        return getId() != null ? getId().hashCode() : 0;
    }
} 