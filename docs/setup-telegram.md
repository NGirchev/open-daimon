# Setting up a Telegram Bot

## Step 1: Create a bot via @BotFather

1. Open Telegram and search for **@BotFather**
2. Send the command `/newbot`
3. Enter a display name for your bot (e.g. `My AI Assistant`)
4. Enter a username — must end in `bot` (e.g. `my_ai_assistant_bot`)
5. BotFather will reply with a token like:
   ```
   1234567890:ABCdefGHIjklMNOpqrsTUVwxyz
   ```
   This is your **`TELEGRAM_TOKEN`**.
6. The username you chose (without `@`) is your **`TELEGRAM_USERNAME`**.

Keep your token secret — anyone with it can control your bot. If it leaks, revoke it immediately (see below).

## Step 2: Get your Telegram user ID

Your admin ID is your numeric Telegram user ID. To find it:

1. Open Telegram and search for **@userinfobot**
2. Send any message (e.g. `/start`)
3. The bot replies with your info:
   ```
   Id: 123456789
   First: John
   ...
   ```
   The `Id` value is your **`ADMIN_TELEGRAM_ID`**.

## Step 3: (Optional) Allow bot in groups

If you plan to add the bot to group chats:

1. In BotFather, send `/mybots`
2. Select your bot
3. Go to **Bot Settings → Group Privacy**
4. Click **Turn off** — this allows the bot to read all group messages

## Step 4: (Optional) Allow bot to join groups

In BotFather:

1. Send `/mybots` → select your bot
2. Go to **Bot Settings → Allow Groups** → **Turn on**

## Revoking a token

If your token is compromised:

1. In BotFather, send `/mybots`
2. Select your bot → **API Token** → **Revoke current token**
3. Update `TELEGRAM_TOKEN` in your `.env` file and restart the app

## Notes

- The bot will only respond to users listed in `TELEGRAM_ACCESS_*_IDS` or channels in `TELEGRAM_ACCESS_*_CHANNELS`
- As admin, you are added automatically (via `ADMIN_TELEGRAM_ID`)
- See [User Priorities](../README.md#user-priorities-and-bulkhead) for how access levels work
