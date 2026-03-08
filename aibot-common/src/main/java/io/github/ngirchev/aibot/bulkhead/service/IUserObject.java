package io.github.ngirchev.aibot.bulkhead.service;

public interface IUserObject {
    Boolean getIsBlocked();
    Boolean getIsPremium();
    Boolean getIsAdmin();
}
