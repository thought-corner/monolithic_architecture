package com.study.monolithic_architecture.service.compensation;

import com.study.monolithic_architecture.constants.CompensationType;

/**
 * 되돌리기 한 종류를 수행한다. (FR-07, BR-5)
 *
 * <p>보상 종류는 늘어날 축이다. 쿠폰 복원, 포인트 반환, 배송 취소가 생길 때마다
 * 분기문을 열지 않고 구현을 하나 추가하면 되도록 한다.
 *
 * <p>구현은 <b>멱등이어야 한다.</b> 이미 되돌려져 있으면 아무것도 하지 않고 성공한다.
 * 정산이 같은 작업을 다시 부를 수 있기 때문이다. (§8 R-3)
 */
public interface CompensationHandler {

    /** 이 구현이 담당하는 보상 종류. */
    CompensationType type();

    /** 되돌린다. 실패하면 예외를 던진다. 재시도 여부는 호출부가 정한다. */
    void compensate(String orderNo);
}
