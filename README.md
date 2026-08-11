
### 프로젝트 설명
---
예약, 결제, 리워드 지급 등 동시성 이슈가 발생할 수 있는 도메인을 직접 구현하며, 상황에 적합한 동시성 전략을 적용하는 것을 목표로 진행한 프로젝트입니다.

- 예약 생성 시 조건부 UPDATE( 비어 있는 좌석만 원자적으로 선점 )로 동일 좌석에 대한 중복 예약을 방지하고, 멱등성 키( DB unique 제약 기반 선점 )를 적용하여 동일 요청이 반복되더라도
  예약이 한 건만 생성되고 동일한 응답이 반환되도록 하였습니다.

- 예약, 예약 취소, 리워드 지급 과정에서 회원 자원( 크레딧, 리워드 )에 DB 비관적 락을 적용하여 정합성을 보장하고, 예약 취소와 리워드 지급은 상태 체크( 예약 상태, 지급 일자)를 통해 멱등성을 확보하였습니다.<br><br>

 **구현 과정 블로그** : [예약 프로젝트 구현 과정 (Velog)](https://velog.io/@ayeah77/series/%EC%98%88%EC%95%BD-%ED%94%84%EB%A1%9C%EC%A0%9C%ED%8A%B8)<br><br>

### 기술 스택
---
Backend : Kotlin, Spring Boot, Spring MVC, Spring Data JPA, Spring Security

Frontend : React, JavaScript

Database / Cache : MySQL, Redis

Infrastructure : AWS EC2, Docker, Docker Compose

Monitoring : Spring Actuator, Micrometer

Test : JUnit5, k6

Etc : SMTP (Spring Mail), Outbox Pattern<br><br>

### 아키텍처 
---
<p align="center">
<img width="1774" height="887" alt="Image" src="https://github.com/user-attachments/assets/72b94daa-cca3-44ef-bb63-661c0f8d3674" /><br><br>

**예약 진행 플로우**

```
대기열 통과 → 좌석 선택(~15초) → 결제창 이동 → 좌석 임시 점유(5분) → 결제 성공 → 예약 확정
                                          └─ 취소/만료 → 좌석 반납 → 홈 화면
```
<br>

### 동시성 이슈
---
여러 요청이 동시에 동일한 자원에 접근하거나 변경하는 상황에서는 동시성 문제가 발생할 수 있습니다.

비즈니스 로직 내부에 예외 처리를 해두었더라도, 각 요청이 독립적인 트랜잭션으로 처리되면서 모두 정상 수행된 것처럼 보일 수 있습니다. 그 결과 좌석의 중복 예약이나 리워드의 중복 지급과 같은 데이터 정합성 문제가 발생할 수 있습니다.

이번 프로젝트에서는 조건부 UPDATE(좌석 선점), DB 비관적 락(회원 크레딧,리워드), 멱등성(unique 제약 + 상태 체크)을 선택적으로 적용하여 동시성 문제를 해결했습니다. 동시성 제어를 DB 계층에서 보장하여 여러 요청이 동시에 처리되는 상황에서도 데이터 정합성을 유지했습니다.<br><br>

### 기능 구현
---
- 좌석 예약에는 조건부 UPDATE, 크레딧/리워드에는 비관적 락을 적용해 경합 특성에 맞는 동시성 제어 전략을 구현했습니다.
    
- 좌석 예약을 `Hold → Confirm → Release` 구조로 구현하고, `HELD` 상태와 `heldUntil`을 이용해 결제 중에도 트랜잭션 없이 좌석을 보호했습니다.
    
- 멱등성 키를 활용해 중복 요청을 원자적으로 처리하고, 동일 요청의 재시도 시 저장된 응답을 반환해 이중 결제를 방지했습니다.
    
- Transactional Outbox Pattern을 적용해 예약 트랜잭션과 이메일 발송을 분리하고 발송 실패 시 재시도할 수 있도록 구현했습니다.
    
- 통합 및 동시성 테스트를 통해 이중 예약 방지, 홀드/확정 경합, 멱등성, 리워드 지급을 검증했습니다.

- DTO 구조 경량화와 Redis 캐시 도입으로 좌석 조회 시간을 최대 "8,404ms에서 912ms까지 약 9.2배 개선"했습니다.

- 테스트 신뢰성 확보를 위해 k6 전용 EC2를 분리 구성해 오버헤드를 격리했으며, 단계별 부하 스윕을 통해 SLO에 만족하는 최대 처리 한계인 500 RPS를 측정하였습니다<br><br>

### 테스트
---

[테스트 과정 블로그](https://velog.io/@ayeah77/series/%EC%98%88%EC%95%BD-%ED%94%84%EB%A1%9C%EC%A0%9C%ED%8A%B8)<br><br>

**기본 기능 테스트**

- 기본 예약/취소 기능 및 예약 멱등성 검증
  
- 동시성 테스트를 통한 중복 예약 방지 검증 (예약 1건 보장)
  
- Transactional Outbox 기반 이메일 발송 검증<br><br>

**부하 테스트**

좌석 예약 시스템이 실제 티켓 오픈 상황에서 견딜 수 있는 참가열(동시 사용자) 크기를 확정하기 위해, 아래 4가지 테스트를 순차적으로 진행했습니다<br><br>

[ 부하 테스트 구성 ]

<table>
  <thead>
    <tr>
      <th style="width: 230px; background-color: transparent;">테스트</th>
      <th style="width: 550px; background-color: transparent;">목적</th>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td style="background-color: transparent;">Closed-loop N 스윕</td>
      <td style="background-color: transparent;">처리 가능한 한계점 탐색</td>
    </tr>
    <tr>
      <td style="background-color: transparent;">소크 테스트</td>
      <td style="background-color: transparent;">확정 N에서 장시간 지속 부하 안정성 검증</td>
      
    </tr>
    <tr>
      <td style="background-color: transparent;">스파이크 테스트</td>
      <td style="background-color: transparent;">순간적인 트래픽 폭증 대응력 검증</td>
    </tr>
    <tr>
      <td style="background-color: transparent;">매진 시나리오</td>
      <td style="background-color: transparent;">좌석 경합 시 정합성(이중 예약 여부) 검증</td>
    </tr>
  </tbody>
</table>

<br>

[ 부하 테스트 결론 ]

1. Baseline 진단

   전체 좌석 조회 API를 Pool=10으로 N 스윕한 결과, N=150 부근에서 성능이 급격히 저하됐습니다. DB CPU는 46%로 여유가 있었지만 커넥션 대기가 최대 190개까지 발생해, 병목은 DB가 아닌 커넥션 점유 시간이라고 판단했습니다.

3. DTO Projection

   불필요한 데이터까지 조회하던 구조를 필요한 데이터만 조회하도록 개선해, 병목 지점을 N=500까지 지연시켰습니다.

4. Redis 캐싱

   N=700 이상에서 다시 병목이 발생했습니다. Redis 캐싱으로 읽기 부하를 줄였지만, 병목은 hold/confirm과 같은 쓰기 경로로 이동했습니다.

5. 구역 단위 조회

   10,000석 전체를 조회하던 방식을 약 250석 단위의 구역 조회로 재설계했습니다. Pool=10을 유지한 상태에서도 N=2,000까지 안정적으로 처리할 수 있었습니다.

6. Pool 및 참가열 확정

   콜드/웜 상태에 따라 성능 차이가 큰 것을 확인해 웜 상태를 기준으로 재검증했습니다. N=2,000에서 30분 Soak 테스트를 통과했으며, hold p99 63ms<br><br>

따라서, 최종 참가열 규모는 N=2,000으로 확정했습니다. 
