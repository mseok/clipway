package dev.mseok.clipway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OtpExtractorTest {
    @Test
    fun findsCodesInVerificationMessages() {
        val cases = mapOf(
            "[Web발신]\n[카카오] 인증번호 [482913] 타인에게 절대 알려주지 마세요." to "482913",
            "[Web발신]\n[PASS] 본인확인 인증번호[739201]를 입력해 주세요." to "739201",
            "[국민은행] 인증번호는 381924 입니다. 유효시간 3분" to "381924",
            "[Web발신]\n네이버 인증번호 [2948]를 입력해주세요." to "2948",
            "[Web발신]\n[토스] 인증 번호: 5820\n문의 1599-4905" to "5820",
            "[쿠팡] 보안코드 91827364 를 입력하세요" to "91827364",
            "G-582931 is your Google verification code." to "582931",
            "Your verification code is 1234" to "1234",
            "123 456 is your Instagram code" to "123456",
            "Use 778-201 as your security code." to "778201",
            "[Web발신]\n(2026-10-03) 인증번호 640118 를 3분 내 입력" to "640118",
        )
        for ((body, code) in cases) assertEquals(body, code, OtpExtractor.extract(body))
    }

    @Test
    fun ignoresOrdinaryMessages() {
        val bodies = listOf(
            "[Web발신]\n신한카드(1234) 승인 홍*동 12,000원 일시불 10/03 21:15 스타벅스",
            "문의는 1588-1234 로 연락주세요",
            "택배가 오늘 도착 예정입니다. 운송장 123456789012",
            "오늘 저녁 7시에 봬요",
            "[Web발신]\n(광고) 10월 한정 50000원 쿠폰 지급",
            "인증번호를 요청하신 적이 없다면 고객센터 1588-1234 로 문의하세요",
        )
        for (body in bodies) assertNull(body, OtpExtractor.extract(body))
    }
}
