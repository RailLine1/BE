package app.daybit.bit

import app.daybit.bit.generation.GeneratedQuestion
import app.daybit.bit.generation.Verdict
import app.daybit.bit.generation.passes
import app.daybit.bit.generation.reorder
import app.daybit.bit.generation.shuffle
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** 보기 섞기 · 검수 판정 (#52). Spring 컨텍스트 없음 */
class ShuffleTest {

    private fun q(answer: Int) = GeneratedQuestion("c", "stem", listOf("A", "B", "C", "D"), answer, "e", 1, "quote")

    private fun permutations(xs: List<Int>): List<List<Int>> =
        if (xs.isEmpty()) listOf(emptyList()) else xs.flatMap { x -> permutations(xs - x).map { listOf(x) + it } }

    @Test
    fun `보기 순서 24가지 × 정답 위치 4가지에서 정답 내용과 보기 4개가 그대로예요`() {
        val orders = permutations(listOf(0, 1, 2, 3))
        assertThat(orders).hasSize(24)
        for (answer in 0..3) for (order in orders) {
            val before = q(answer)
            val after = reorder(before, order)
            assertThat(after.choices[after.answerIdx]).isEqualTo(before.choices[answer])
            assertThat(after.choices).containsExactlyInAnyOrderElementsOf(before.choices)
            assertThat(after.copy(choices = before.choices, answerIdx = answer)).isEqualTo(before)   // 나머지 필드는 그대로
        }
    }

    @Test
    fun `정답 위치가 한쪽에 몰리지 않아요`() {
        val random = Random(42)   // 테스트에서만 시드를 고정해요
        val positions = (1..400).map { shuffle(q(0), random).answerIdx }.groupingBy { it }.eachCount()
        assertThat(positions.keys).containsExactlyInAnyOrder(0, 1, 2, 3)
        assertThat(positions.values).allSatisfy { assertThat(it).isBetween(60, 140) }
    }

    @Test
    fun `order 가 보기 번호를 한 번씩 담지 않으면 거절해요`() {
        assertThatThrownBy { reorder(q(0), listOf(0, 0, 1, 2)) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun v(vararg m: String) = m.mapIndexed { i, s -> Verdict(i, s) }

    @Test
    fun `맞는 보기가 정확히 하나이고 저장된 정답일 때만 통과해요`() {
        assertThat(passes(v("no", "yes", "no", "no"), 1)).isTrue()
        assertThat(passes(v("no", "yes", "no", "no"), 0)).isFalse()       // 정답이 다름
        assertThat(passes(v("yes", "yes", "no", "no"), 0)).isFalse()      // 정답이 둘
        assertThat(passes(v("no", "yes", "unsure", "no"), 1)).isFalse()   // 모르는 보기
        assertThat(passes(v("no", "yes", "no"), 1)).isFalse()             // 판정 누락
        assertThat(passes(null, 1)).isFalse()                             // 응답 없음
        assertThat(passes(listOf(Verdict(1, "yes"), Verdict(1, "no"), Verdict(2, "no"), Verdict(3, "no")), 1)).isFalse()  // index 중복
    }
}
