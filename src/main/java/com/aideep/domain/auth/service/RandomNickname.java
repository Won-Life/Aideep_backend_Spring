package com.aideep.domain.auth.service;

import java.security.SecureRandom;
import java.util.List;
import java.util.Random;

/**
 * 회원가입 시 사용자에게 지정할 임의 닉네임을 만든다. "형용사 + 명사 + 네 자리 숫자" 형식이며,
 * users.username에 unique 제약이 없으므로 중복은 허용한다.
 */
public final class RandomNickname {
    static final List<String> ADJECTIVES = List.of(
            "용감한", "다정한", "성실한", "엉뚱한", "느긋한", "활발한", "포근한", "똑똑한", "조용한", "신나는",
            "상냥한", "담백한", "재빠른", "든든한", "말끔한", "수줍은", "씩씩한", "유쾌한", "차분한", "행복한");
    static final List<String> NOUNS = List.of(
            "다람쥐", "고양이", "강아지", "너구리", "수달", "참새", "고래", "펭귄", "여우", "사슴",
            "두루미", "토끼", "거북이", "올빼미", "코알라", "하마", "물개", "청설모", "기린", "판다");
    private static final int NUMBER_ORIGIN = 1000;
    private static final int NUMBER_BOUND = 10000;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private RandomNickname() {
    }

    public static String generate() {
        return generate(SECURE_RANDOM);
    }

    /** 테스트에서 고정 시드를 주입할 수 있도록 분리한다. */
    static String generate(Random random) {
        String adjective = ADJECTIVES.get(random.nextInt(ADJECTIVES.size()));
        String noun = NOUNS.get(random.nextInt(NOUNS.size()));
        int number = NUMBER_ORIGIN + random.nextInt(NUMBER_BOUND - NUMBER_ORIGIN);
        return adjective + noun + number;
    }
}
