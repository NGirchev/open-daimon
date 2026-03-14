package io.github.ngirchev.opendaimon.bulkhead.service;

public interface IUserObject {
    Boolean getIsBlocked();
    Boolean getIsPremium();
    Boolean getIsAdmin();
}
