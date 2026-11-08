package app.velin.core.domain

/** Upstream src/platform-kit/app-env/TutanotaConstants.ts:375-400. */
fun mailAuthenticationFromWire(value: String?): MailAuthentication = when (value) {
    "0" -> MailAuthentication.Authenticated
    "1" -> MailAuthentication.HardFail
    "2" -> MailAuthentication.SoftFail
    "3" -> MailAuthentication.InvalidMailFrom
    "4" -> MailAuthentication.MissingMailFrom
    else -> MailAuthentication.Unknown
}

/** Upstream MailViewerViewModel.ts:409-441. Null means details have not loaded. */
fun mailFinding(suspicious: Boolean, warningDismissed: Boolean,
    authentication: MailAuthentication?, rsaDespiteTutaCrypt: Boolean): MailFinding? = when {
    suspicious -> MailFinding.Phishing
    warningDismissed -> null
    authentication != null && authentication != MailAuthentication.Authenticated &&
        authentication != MailAuthentication.SoftFail -> MailFinding.AuthenticationFailed
    rsaDespiteTutaCrypt -> MailFinding.DeprecatedSenderKey
    authentication == MailAuthentication.SoftFail -> MailFinding.AuthenticationUncertain
    else -> null
}
