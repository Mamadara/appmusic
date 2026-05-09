# AppMusic — Telegram Remote Control

Android app that connects to a Telegram bot for remote device control. Control your phone from anywhere using Telegram commands.

## Features

- **Device registration** — auto-registers with a unique device ID on first launch
- **Multi-device support** — manage multiple Android devices from one Telegram chat
- **Telegram commands**: `/start`, `/devices`, `/ping`, `/info`
- **Device selection** — pick which device to control via inline keyboard
- **Minimal UI** — just shows "Connecté à Telegram" status

## Requirements

- Android 8.0+ (API 26)
- Internet connection
- Telegram Bot Token (set in `TelegramBot.kt`)

## Setup

1. Create a bot with [@BotFather](https://t.me/BotFather) on Telegram
2. Copy the bot token into `src/app/src/main/java/com/company/product/TelegramBot.kt`
3. Build and install on your device
4. Start a chat with your bot and send `/start`

## Development

Built with Kotlin, Coroutines. Follows Material Design 3.

```bash
cd src
./gradlew assembleDebug
```
