package com.aideep.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.stream.IntStream;

/** 가입 시 지정하는 임의 닉네임의 형식과 구성 요소를 검증한다. */
class RandomNicknameTest {

    private static final String PATTERN = "[가-힣]+\\d{4}";

    @Test
    void generatesAdjectiveNounAndFourDigits() {
        String nickname = RandomNickname.generate(new Random(42));

        assertThat(nickname).matches(PATTERN);
        assertThat(nickname).hasSizeLessThanOrEqualTo(100);
    }

    @Test
    void usesOnlyDefinedWordsAndNumberRange() {
        Random random = new Random(7);

        IntStream.range(0, 500).forEach(i -> {
            String nickname = RandomNickname.generate(random);
            String adjective = RandomNickname.ADJECTIVES.stream().filter(nickname::startsWith).findFirst()
                    .orElseThrow(() -> new AssertionError("형용사 목록에 없는 값: " + nickname));
            String rest = nickname.substring(adjective.length());
            String noun = RandomNickname.NOUNS.stream().filter(rest::startsWith).findFirst()
                    .orElseThrow(() -> new AssertionError("명사 목록에 없는 값: " + nickname));
            assertThat(Integer.parseInt(rest.substring(noun.length()))).isBetween(1000, 9999);
        });
    }

    @Test
    void sameSeedProducesSameNickname() {
        assertThat(RandomNickname.generate(new Random(1))).isEqualTo(RandomNickname.generate(new Random(1)));
    }

    @Test
    void secureRandomGeneratesVaryingNicknames() {
        assertThat(IntStream.range(0, 50).mapToObj(i -> RandomNickname.generate()).distinct().count())
                .as("기본 생성기는 매번 같은 값을 주지 않는다")
                .isGreaterThan(1);
        assertThat(RandomNickname.generate()).matches(PATTERN);
    }
}
