package io.github.ngirchev.aibot.common.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import io.github.ngirchev.aibot.common.model.Bugreport;
import io.github.ngirchev.aibot.common.model.BugreportType;
import io.github.ngirchev.aibot.common.model.User;
import io.github.ngirchev.aibot.common.repository.BugreportRepository;

@Slf4j
@RequiredArgsConstructor
public class BugreportService {
    
    private final BugreportRepository bugreportRepository;
    
    @Transactional
    public Bugreport saveBug(User user, String text) {
        log.debug("Saving bug report for user: {}", user.getId());
        Bugreport bugreport = new Bugreport();
        bugreport.setUser(user);
        bugreport.setText(text);
        bugreport.setType(BugreportType.BUG);
        return bugreportRepository.save(bugreport);
    }

    @Transactional
    public Bugreport saveImprovementProposal(User user, String text) {
        log.debug("Saving improvement proposal for user: {}", user.getId());
        Bugreport bugreport = new Bugreport();
        bugreport.setUser(user);
        bugreport.setText(text);
        bugreport.setType(BugreportType.IMPROVEMENT);
        return bugreportRepository.save(bugreport);
    }
}
