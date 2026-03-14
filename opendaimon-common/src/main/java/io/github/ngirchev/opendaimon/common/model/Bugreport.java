package io.github.ngirchev.opendaimon.common.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.OffsetDateTime;

@Entity
@Table(name = "bugreport")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class Bugreport extends AbstractEntity<Long> {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    
    @Column(name = "text", nullable = false, columnDefinition = "TEXT")
    private String text;
    
    @Column(name = "type", nullable = false)
    @Enumerated(EnumType.STRING)
    private BugreportType type;
    
    @Column(name = "created_at")
    private OffsetDateTime createdAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
    }
}

