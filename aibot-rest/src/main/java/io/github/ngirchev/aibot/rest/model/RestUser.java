package io.github.ngirchev.aibot.rest.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import io.github.ngirchev.aibot.common.model.User;

@Entity
@Table(name = "rest_user")
@DiscriminatorValue("REST")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class RestUser extends User {
    
    @Column(name = "email", unique = true)
    private String email;
} 