# Setting up Serper (Web Search)

Serper provides Google Search results via API. It is **optional** — OpenDaimon works without it, but web search features will be disabled.

When enabled, the AI can search the web in real time to answer questions about current events.

## Step 1: Create an account

Go to [serper.dev](https://serper.dev) and sign up. New accounts receive **2,500 free searches**.

## Step 2: Get an API key

1. Log in to your dashboard
2. Your API key is shown on the main page
3. Copy it — this is your **`SERPER_KEY`**

## Step 3: Add it to your config

Set `SERPER_KEY` in your `.env` file:

```
SERPER_KEY=your_key_here
```

Then restart the app:

```bash
docker compose restart opendaimon-app
```

## Notes

- If `SERPER_KEY` is empty or not set, web search is silently disabled
- The bot will still work for direct AI conversation without Serper
- After 2,500 free searches, you'll need a paid plan (starting at $50/month for 50,000 searches)
- Usage is shown at [serper.dev/dashboard](https://serper.dev/dashboard)
