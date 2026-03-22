package io.github.ngirchev.opendaimon.bulkhead.service;

public interface IWhitelistService {
    boolean isUserAllowed(Long userId);
    boolean checkUserInChannel(Long userId);
    void addToWhitelist(Long userId);
}
