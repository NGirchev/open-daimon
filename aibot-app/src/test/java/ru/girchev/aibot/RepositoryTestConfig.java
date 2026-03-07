package ru.girchev.aibot;

import org.springframework.boot.SpringBootConfiguration;

/**
 * Минимальная конфигурация для поиска @SpringBootConfiguration при сканировании пакетов
 * из тестов репозиториев (ru.girchev.aibot.rest.repository, ru.girchev.aibot.telegram.repository).
 * Без этого класса slice-тесты (@DataJpaTest) не находят конфигурацию и падают с
 * "Unable to find a @SpringBootConfiguration".
 */
@SpringBootConfiguration
public class RepositoryTestConfig {
}
