package io.github.ngirchev.opendaimon.rest.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import io.github.ngirchev.opendaimon.common.model.User;

@Entity
@Table(name = "rest_user")
@PrimaryKeyJoinColumn(name = "id")
@DiscriminatorValue("REST")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class RestUser extends User {

    @Column(name = "email", unique = true, nullable = false)
    private String email;
} 