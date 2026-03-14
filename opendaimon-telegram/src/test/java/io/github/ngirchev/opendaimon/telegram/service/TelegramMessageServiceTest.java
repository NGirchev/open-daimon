package io.github.ngirchev.opendaimon.telegram.service;

import io.github.ngirchev.opendaimon.common.config.CoreCommonProperties;
import io.github.ngirchev.opendaimon.common.model.OpenDaimonMessage;
import io.github.ngirchev.opendaimon.common.model.AssistantRole;
import io.github.ngirchev.opendaimon.common.model.Attachment;
import io.github.ngirchev.opendaimon.common.model.AttachmentType;
import io.github.ngirchev.opendaimon.common.model.RequestType;
import io.github.ngirchev.opendaimon.common.service.OpenDaimonMessageService;
import io.github.ngirchev.opendaimon.common.storage.config.StorageProperties;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUser;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUserSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoSettings;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TelegramMessageServiceTest {

    @Mock
    private OpenDaimonMessageService messageService;
    @Mock
    private TelegramUserService telegramUserService;
    @Mock
    private CoreCommonProperties coreCommonProperties;
    @Mock
    private ObjectProvider<StorageProperties> storagePropertiesProvider;
    @Mock
    private ObjectProvider<TelegramMessageService> selfProvider;

    private TelegramMessageService telegramMessageService;
    private TelegramUser telegramUser;
    private AssistantRole assistantRole;

    @BeforeEach
    void setUp() {
        telegramMessageService = new TelegramMessageService(
                messageService,
                telegramUserService,
                coreCommonProperties,
                storagePropertiesProvider,
                selfProvider);
        telegramUser = new TelegramUser();
        telegramUser.setId(1L);
        assistantRole = new AssistantRole();
        assistantRole.setId(10L);
        when(telegramUserService.getOrCreateAssistantRole(any(TelegramUser.class), any())).thenReturn(assistantRole);
        when(coreCommonProperties.getAssistantRole()).thenReturn("Default role");
    }

    @Test
    void saveUserMessage_withSession_buildsMetadataAndCallsMessageService() {
        TelegramUserSession session = new TelegramUserSession();
        session.setId(100L);
        OpenDaimonMessage saved = new OpenDaimonMessage();
        when(messageService.saveUserMessage(
                eq(telegramUser), eq("Hello"), eq(RequestType.TEXT), eq(assistantRole),
                any(Map.class), isNull())).thenReturn(saved);

        OpenDaimonMessage result = telegramMessageService.saveUserMessage(
                telegramUser, session, "Hello", RequestType.TEXT, null, null);

        assertNotNull(result);
        assertEquals(saved, result);
        verify(messageService).saveUserMessage(
                eq(telegramUser), eq("Hello"), eq(RequestType.TEXT), eq(assistantRole),
                any(Map.class), isNull());
    }

    @Test
    void saveUserMessage_withoutSession_noMetadata() {
        OpenDaimonMessage saved = new OpenDaimonMessage();
        when(messageService.saveUserMessage(
                eq(telegramUser), eq("Hi"), eq(RequestType.TEXT), eq(assistantRole),
                isNull(), isNull())).thenReturn(saved);

        OpenDaimonMessage result = telegramMessageService.saveUserMessage(
                telegramUser, null, "Hi", RequestType.TEXT, null, null);

        assertNotNull(result);
        verify(messageService).saveUserMessage(
                eq(telegramUser), eq("Hi"), eq(RequestType.TEXT), eq(assistantRole),
                isNull(), isNull());
    }

    @Test
    void saveUserMessage_withCustomRole_usesCustomRole() {
        when(telegramUserService.getOrCreateAssistantRole(eq(telegramUser), eq("Custom role")))
                .thenReturn(assistantRole);
        OpenDaimonMessage saved = new OpenDaimonMessage();
        when(messageService.saveUserMessage(any(), any(), any(), eq(assistantRole), any(), any()))
                .thenReturn(saved);

        OpenDaimonMessage result = telegramMessageService.saveUserMessage(
                telegramUser, null, "Hi", RequestType.TEXT, "Custom role", null);

        assertNotNull(result);
        verify(telegramUserService).getOrCreateAssistantRole(telegramUser, "Custom role");
    }

    @Test
    void saveUserMessage_withAttachmentsAndStorageEnabled_buildsAttachmentRefs() {
        StorageProperties storage = new StorageProperties();
        storage.setEnabled(true);
        StorageProperties.MinioProperties minio = new StorageProperties.MinioProperties();
        minio.setTtlHours(24);
        storage.setMinio(minio);
        when(storagePropertiesProvider.getIfAvailable()).thenReturn(storage);

        Attachment att = new Attachment("key1", "image/png", "photo.png", 100L, AttachmentType.IMAGE, new byte[0]);
        OpenDaimonMessage saved = new OpenDaimonMessage();
        when(messageService.saveUserMessage(
                eq(telegramUser), any(), any(), eq(assistantRole), any(), any(List.class)))
                .thenReturn(saved);

        OpenDaimonMessage result = telegramMessageService.saveUserMessage(
                telegramUser, null, "See image", RequestType.TEXT, null, List.of(att));

        assertNotNull(result);
        verify(messageService).saveUserMessage(
                eq(telegramUser), eq("See image"), eq(RequestType.TEXT), eq(assistantRole),
                any(), any(List.class));
    }

    @Test
    void saveUserMessage_withAttachmentsStorageUnavailable_noAttachmentRefs() {
        when(storagePropertiesProvider.getIfAvailable()).thenReturn(null);
        Attachment att = new Attachment("k", "image/jpeg", "f.jpg", 1L, AttachmentType.IMAGE, new byte[0]);

        OpenDaimonMessage saved = new OpenDaimonMessage();
        when(messageService.saveUserMessage(
                eq(telegramUser), any(), any(), eq(assistantRole), any(), isNull()))
                .thenReturn(saved);

        OpenDaimonMessage result = telegramMessageService.saveUserMessage(
                telegramUser, null, "Pic", RequestType.TEXT, null, List.of(att));

        assertNotNull(result);
        verify(messageService).saveUserMessage(
                eq(telegramUser), eq("Pic"), eq(RequestType.TEXT), eq(assistantRole),
                any(), isNull());
    }

    @Test
    void saveAssistantMessage_withResponseDataMap_callsMessageService() {
        Map<String, Object> responseData = Map.of("usage", 100);
        OpenDaimonMessage saved = new OpenDaimonMessage();
        when(messageService.saveAssistantMessage(
                eq(telegramUser), eq("Reply"), eq("openai"), eq(assistantRole), eq(500), eq(responseData)))
                .thenReturn(saved);

        OpenDaimonMessage result = telegramMessageService.saveAssistantMessage(
                telegramUser, "Reply", "openai", null, 500, responseData);

        assertNotNull(result);
        verify(messageService).saveAssistantMessage(
                eq(telegramUser), eq("Reply"), eq("openai"), eq(assistantRole), eq(500), eq(responseData));
    }

    @Test
    void saveAssistantMessage_overloadWithoutMap_callsSelfProvider() {
        when(selfProvider.getObject()).thenReturn(telegramMessageService);
        OpenDaimonMessage saved = new OpenDaimonMessage();
        when(messageService.saveAssistantMessage(
                eq(telegramUser), eq("Ok"), eq("ollama"), eq(assistantRole), eq(100), isNull()))
                .thenReturn(saved);

        OpenDaimonMessage result = telegramMessageService.saveAssistantMessage(
                telegramUser, "Ok", "ollama", null, 100);

        assertNotNull(result);
        verify(selfProvider).getObject();
        verify(messageService).saveAssistantMessage(
                eq(telegramUser), eq("Ok"), eq("ollama"), eq(assistantRole), eq(100), isNull());
    }

    @Test
    void saveAssistantErrorMessage_usesRoleAndCallsMessageService() {
        OpenDaimonMessage saved = new OpenDaimonMessage();
        when(messageService.saveAssistantErrorMessage(
                eq(telegramUser), eq("Error"), eq("api"), eq(assistantRole), eq("details")))
                .thenReturn(saved);

        OpenDaimonMessage result = telegramMessageService.saveAssistantErrorMessage(
                telegramUser, "Error", "api", null, "details");

        assertNotNull(result);
        verify(messageService).saveAssistantErrorMessage(
                eq(telegramUser), eq("Error"), eq("api"), eq(assistantRole), eq("details"));
    }

    @Test
    void saveAssistantErrorMessage_withCustomRole_usesCustomRole() {
        when(telegramUserService.getOrCreateAssistantRole(eq(telegramUser), eq("Custom")))
                .thenReturn(assistantRole);
        OpenDaimonMessage saved = new OpenDaimonMessage();
        when(messageService.saveAssistantErrorMessage(any(), any(), any(), eq(assistantRole), any()))
                .thenReturn(saved);

        telegramMessageService.saveAssistantErrorMessage(
                telegramUser, "Err", "svc", "Custom", "data");

        verify(telegramUserService).getOrCreateAssistantRole(telegramUser, "Custom");
    }
}
