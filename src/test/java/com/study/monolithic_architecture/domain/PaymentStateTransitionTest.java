package com.study.monolithic_architecture.domain;

import com.study.monolithic_architecture.constants.PaymentStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 결제 상태 전이 규칙. 저장소도 스프링도 없이 규칙만 본다.
 *
 * <p>가드가 막아야 하는 것은 <b>확정된 결과를 다른 결과로 뒤집는 일</b>이다.
 * 같은 결과를 다시 확인하는 것은 오류가 아니다. 우리 기록을 갱신하는 경로가 둘 이상이고
 * (요청 응답 반영, 정산의 조회 해소) 둘 다 같은 대행 원장을 읽으므로, 같은 결론에
 * 두 번 도달하는 것은 정상 동작이다. 이것을 예외로 만들면 정상 경로가 깨진다.
 */
class PaymentStateTransitionTest {

	private static final long AMOUNT = 30_000L;

	private Payment requested() {
		return new Payment("ORD-1", AMOUNT);
	}

	@Test
	@DisplayName("요청 직후에는 결과가 미확인이다")
	void 요청_직후는_미확인() {
		assertThat(requested().getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
	}

	@Nested
	@DisplayName("같은 결과의 재확인은 오류가 아니다")
	class SameOutcomeReconfirmation {

		@Test
		@DisplayName("승인을 두 번 확인해도 승인으로 남는다")
		void 승인_재확인() {
			Payment payment = requested();
			payment.approve();

			assertThatCode(payment::approve)
				.as("요청 응답 반영과 정산의 조회 해소가 같은 승인에 도달하는 것은 정상이다. "
					+ "여기서 예외가 나면 주문 처리가 중단되고, 승인된 결제가 취소 대상이 된다")
				.doesNotThrowAnyException();
			assertThat(payment.getStatus()).isEqualTo(PaymentStatus.APPROVED);
		}

		@Test
		@DisplayName("거절을 두 번 확인해도 거절로 남는다")
		void 거절_재확인() {
			Payment payment = requested();
			payment.decline();

			assertThatCode(payment::decline).doesNotThrowAnyException();
			assertThat(payment.getStatus()).isEqualTo(PaymentStatus.DECLINED);
		}
	}

	@Nested
	@DisplayName("확정된 결과를 다른 결과로 뒤집을 수 없다")
	class OutcomeCannotBeFlipped {

		@Test
		@DisplayName("승인된 결제를 거절로 바꿀 수 없다")
		void 승인은_거절이_되지_않는다() {
			Payment payment = requested();
			payment.approve();

			assertThatThrownBy(payment::decline).isInstanceOf(IllegalStateException.class);
		}

		@Test
		@DisplayName("거절된 결제를 승인으로 바꿀 수 없다")
		void 거절은_승인이_되지_않는다() {
			Payment payment = requested();
			payment.decline();

			assertThatThrownBy(payment::approve).isInstanceOf(IllegalStateException.class);
		}
	}

	@Nested
	@DisplayName("취소는 조회로 결과를 확정한 뒤에만")
	class Cancellation {

		@Test
		@DisplayName("미확인 결제는 취소할 수 없다")
		void 미확인은_취소_불가() {
			assertThatThrownBy(requested()::cancel)
				.as("승인 여부를 모르는 채 취소로 닫으면 뒤늦은 승인이 되돌려지지 않는다")
				.isInstanceOf(IllegalStateException.class);
		}

		@Test
		@DisplayName("승인된 결제는 취소된다")
		void 승인은_취소된다() {
			Payment payment = requested();
			payment.approve();
			payment.cancel();

			assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
		}

		@Test
		@DisplayName("이미 취소됐거나 승인된 적 없으면 되돌릴 것이 없다")
		void 되돌릴_것이_없으면_성공이다() {
			Payment cancelled = requested();
			cancelled.approve();
			cancelled.cancel();
			assertThatCode(cancelled::cancel).doesNotThrowAnyException();
			assertThat(cancelled.getStatus()).isEqualTo(PaymentStatus.CANCELLED);

			Payment declined = requested();
			declined.decline();
			assertThatCode(declined::cancel).doesNotThrowAnyException();
			assertThat(declined.getStatus()).isEqualTo(PaymentStatus.DECLINED);
		}
	}

	@Nested
	@DisplayName("대행이 이미 취소한 결제")
	class CancelledByGateway {

		@Test
		@DisplayName("승인이 있었다는 사실과 취소를 함께 남긴다")
		void 승인_사실과_취소를_함께_남긴다() {
			Payment payment = requested();
			payment.confirmCancelled();

			assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
		}

		@Test
		@DisplayName("다시 확인해도 오류가 아니다")
		void 재확인해도_오류가_아니다() {
			Payment payment = requested();
			payment.confirmCancelled();

			assertThatCode(payment::confirmCancelled)
				.as("조회 해소와 보상이 같은 취소 사실에 도달하는 것은 정상이다")
				.doesNotThrowAnyException();
			assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
		}

		@Test
		@DisplayName("승인이 확인된 결제에도 적용된다")
		void 승인된_결제에도_적용된다() {
			Payment payment = requested();
			payment.approve();

			assertThatCode(payment::confirmCancelled).doesNotThrowAnyException();
			assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
		}
	}
}
