# Wi-Fi тест (dets1602-web/wifi-test)
Android, Kotlin + Compose, три режима: «Поиск места» (Live.kt), «Wi-Fi» (Tester.kt), «Сотовая» (Cell.kt). См. PRODUCT.md и DESIGN.md (скилл «дизайн приложений»).
Сборка: push в main → GitHub Actions → релиз latest, ссылка:
https://github.com/dets1602-web/wifi-test/releases/download/latest/WifiTest.apk
Репозиторий публичный (бесплатные минуты). Ключ подписи — app/debug.keystore, не менять (иначе не обновится поверх).
Локально собрать нельзя (нет доступа к Maven Google) — только через Actions. В среде gh GraphQL не работает, только `gh api`.
