-- V1: 기준 스키마 (2026-10-21, Phase 3 Flyway 도입)
-- 기존 환경(ddl-auto=update가 만든 스키마)의 mysqldump --no-data 를 사람이 검토·정리한 것.
-- 이미 스키마가 있는 DB는 baseline-version=1 로 이 파일을 건너뛴다(application.yml 참조).

CREATE TABLE `members` (
  `member_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `coin` int NOT NULL,
  `experience` int NOT NULL,
  `introduction` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `level` int NOT NULL,
  `login_id` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `nickname` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `password` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `profile_image_key` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`member_id`),
  UNIQUE KEY `UKlq5wej6688i1bd6b5c11neptj` (`login_id`),
  UNIQUE KEY `UKe6u9u9ypoc7oldnpxdjwcdx3` (`nickname`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `character_items` (
  `character_id` bigint NOT NULL AUTO_INCREMENT,
  `description` text COLLATE utf8mb4_unicode_ci NOT NULL,
  `name` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `price` int NOT NULL,
  `summary` varchar(500) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`character_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `parties` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `category` text COLLATE utf8mb4_unicode_ci,
  `current_members` int NOT NULL,
  `description` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `image_url` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `is_public` bit(1) NOT NULL,
  `is_recruiting` bit(1) NOT NULL,
  `max_members` int NOT NULL,
  `name` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `party_password` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `quest_content` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `routines` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `content` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `end_date` date NOT NULL,
  `quest_category` enum('IMPORTANT_NOT_URGENT','IMPORTANT_URGENT','NOT_IMPORTANT_NOT_URGENT','NOT_IMPORTANT_URGENT') COLLATE utf8mb4_unicode_ci NOT NULL,
  `member_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FK2dtk9w0o8yyb92rkweqraoykh` (`member_id`),
  CONSTRAINT `FK2dtk9w0o8yyb92rkweqraoykh` FOREIGN KEY (`member_id`) REFERENCES `members` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `repeat_days` (
  `routine_id` bigint NOT NULL,
  `day_of_week` enum('FRIDAY','MONDAY','SATURDAY','SUNDAY','THURSDAY','TUESDAY','WEDNESDAY') COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`routine_id`,`day_of_week`),
  CONSTRAINT `FKcj5bh7xjfignu8iall3auav4o` FOREIGN KEY (`routine_id`) REFERENCES `routines` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `daily_quests` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `content` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `is_checked` bit(1) NOT NULL,
  `quest_category` enum('IMPORTANT_NOT_URGENT','IMPORTANT_URGENT','NOT_IMPORTANT_NOT_URGENT','NOT_IMPORTANT_URGENT') COLLATE utf8mb4_unicode_ci NOT NULL,
  `quest_date` date NOT NULL,
  `member_id` bigint NOT NULL,
  `routine_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_daily_quests_routine_quest_date` (`routine_id`,`quest_date`),
  KEY `FK47lq7n3cmso4m59sh7bt21vdg` (`member_id`),
  CONSTRAINT `FK47lq7n3cmso4m59sh7bt21vdg` FOREIGN KEY (`member_id`) REFERENCES `members` (`member_id`),
  CONSTRAINT `FKaew2mtwnu0rs1u55mfdl73ikg` FOREIGN KEY (`routine_id`) REFERENCES `routines` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `member_character` (
  `member_character_id` bigint NOT NULL AUTO_INCREMENT,
  `is_equipped` bit(1) NOT NULL,
  `character_id` bigint NOT NULL,
  `member_id` bigint NOT NULL,
  PRIMARY KEY (`member_character_id`),
  UNIQUE KEY `uk_member_character` (`member_id`,`character_id`),
  KEY `FK323yk5lpdjn8x5q6n85c5yki4` (`character_id`),
  CONSTRAINT `FK323yk5lpdjn8x5q6n85c5yki4` FOREIGN KEY (`character_id`) REFERENCES `character_items` (`character_id`),
  CONSTRAINT `FKgy3nlah3k1i8xjrbrs05i11qc` FOREIGN KEY (`member_id`) REFERENCES `members` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `party_members` (
  `party_member_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `role` enum('LEADER','MEMBER') COLLATE utf8mb4_unicode_ci NOT NULL,
  `member_id` bigint NOT NULL,
  `party_id` bigint NOT NULL,
  PRIMARY KEY (`party_member_id`),
  UNIQUE KEY `UKdtj6ckk7icadsdnep56nq2216` (`member_id`,`party_id`),
  KEY `FK8t7tw0lrh904ld026ludm2vm3` (`party_id`),
  CONSTRAINT `FK8t7tw0lrh904ld026ludm2vm3` FOREIGN KEY (`party_id`) REFERENCES `parties` (`id`),
  CONSTRAINT `FKtmjc86risl1hskaxo9i8eadva` FOREIGN KEY (`member_id`) REFERENCES `members` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `chat_messages` (
  `chat_message_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `content` varchar(500) COLLATE utf8mb4_unicode_ci NOT NULL,
  `party_id` bigint NOT NULL,
  `member_id` bigint NOT NULL,
  PRIMARY KEY (`chat_message_id`),
  KEY `FK5ajfkm55pguui2yha71v74ern` (`party_id`),
  KEY `FK7a2wawc6k2qfgy936egdy32wl` (`member_id`),
  CONSTRAINT `FK5ajfkm55pguui2yha71v74ern` FOREIGN KEY (`party_id`) REFERENCES `parties` (`id`),
  CONSTRAINT `FK7a2wawc6k2qfgy936egdy32wl` FOREIGN KEY (`member_id`) REFERENCES `members` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `party_verifications` (
  `party_verification_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `image_url` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `verification_date` date NOT NULL,
  `verified` bit(1) NOT NULL,
  `party_id` bigint NOT NULL,
  `party_member_id` bigint NOT NULL,
  PRIMARY KEY (`party_verification_id`),
  UNIQUE KEY `UK4aiimuvfyg5gx901e7tvh1rbe` (`party_member_id`,`verification_date`),
  KEY `FKb2padrb0pg4gulgjdkna7qxli` (`party_id`),
  CONSTRAINT `FK2q9i166w24wgid9tgb9mj67ou` FOREIGN KEY (`party_member_id`) REFERENCES `party_members` (`party_member_id`),
  CONSTRAINT `FKb2padrb0pg4gulgjdkna7qxli` FOREIGN KEY (`party_id`) REFERENCES `parties` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `streaks` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `consecutive_days` int NOT NULL,
  `last_checked_date` date NOT NULL,
  `start_date` date NOT NULL,
  `member_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FKplne9cx9q0fy0evmiqsn3you7` (`member_id`),
  CONSTRAINT `FKplne9cx9q0fy0evmiqsn3you7` FOREIGN KEY (`member_id`) REFERENCES `members` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
