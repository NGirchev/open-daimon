package io.github.ngirchev.opendaimon.common.service;

import io.github.ngirchev.opendaimon.common.model.Bugreport;
import io.github.ngirchev.opendaimon.common.model.BugreportType;
import io.github.ngirchev.opendaimon.common.model.User;
import io.github.ngirchev.opendaimon.common.repository.BugreportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BugreportServiceTest {

    @Mock
    private BugreportRepository bugreportRepository;
    @Mock
    private User user;

    private BugreportService service;

    @BeforeEach
    void setUp() {
        service = new BugreportService(bugreportRepository);
    }

    @Nested
    @DisplayName("saveBug")
    class SaveBug {

        @Test
        void createsBugreportWithBugTypeAndSaves() {
            when(user.getId()).thenReturn(1L);
            when(bugreportRepository.save(any(Bugreport.class))).thenAnswer(inv -> inv.getArgument(0));

            Bugreport result = service.saveBug(user, "App crashes on startup");

            assertNotNull(result);
            assertEquals(user, result.getUser());
            assertEquals("App crashes on startup", result.getText());
            assertEquals(BugreportType.BUG, result.getType());
            ArgumentCaptor<Bugreport> captor = ArgumentCaptor.forClass(Bugreport.class);
            verify(bugreportRepository).save(captor.capture());
            assertEquals(BugreportType.BUG, captor.getValue().getType());
        }
    }

    @Nested
    @DisplayName("saveImprovementProposal")
    class SaveImprovementProposal {

        @Test
        void createsBugreportWithImprovementTypeAndSaves() {
            when(user.getId()).thenReturn(2L);
            when(bugreportRepository.save(any(Bugreport.class))).thenAnswer(inv -> inv.getArgument(0));

            Bugreport result = service.saveImprovementProposal(user, "Add dark mode");

            assertNotNull(result);
            assertEquals(user, result.getUser());
            assertEquals("Add dark mode", result.getText());
            assertEquals(BugreportType.IMPROVEMENT, result.getType());
            ArgumentCaptor<Bugreport> captor = ArgumentCaptor.forClass(Bugreport.class);
            verify(bugreportRepository).save(captor.capture());
            assertEquals(BugreportType.IMPROVEMENT, captor.getValue().getType());
        }
    }
}
