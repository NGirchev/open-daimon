# Setting up OpenRouter

OpenRouter is a cloud API that provides access to many AI models (GPT, Claude, Gemini, Mistral, Llama, etc.) under a single endpoint. **Free-tier models are available with no payment required.**

## Step 1: Create an account

Go to [openrouter.ai](https://openrouter.ai) and sign up (GitHub OAuth or email).

## Step 2: Get an API key

1. Go to [openrouter.ai/keys](https://openrouter.ai/keys)
2. Click **Create Key**
3. Give it a name (e.g. `open-daimon`)
4. Copy the generated key — this is your **`OPENROUTER_KEY`**

The key starts with `sk-or-v1-...`.

## Step 3: (Optional) Add credits for paid models

Free models work without any credits. To use paid models (GPT-4o, Claude 3.5, etc.):

1. Go to **Settings → Credits**
2. Add a payment method and top up

## Which models are used by OpenDaimon?

| Role | Model tier | Examples |
|------|-----------|---------|
| ADMIN | Any (up to $5/request) | `openrouter/auto`, `anthropic/claude-3.5-sonnet` |
| VIP | Low-cost ($0.50/request) | `google/gemini-flash-1.5`, `mistralai/mistral-7b` |
| REGULAR | Free only | `google/gemini-2.0-flash-exp:free`, `meta-llama/llama-3.1-8b-instruct:free` |

You can customize the model list in `application-local.yml`. See [tariffs-and-models.md](tariffs-and-models.md) for the full model capability matrix.

## Free model rotation

OpenDaimon can automatically rotate through available free OpenRouter models. This is enabled by default for VIP/REGULAR users. The model list is refreshed every 24 hours.

To disable rotation and use a fixed list, set in `application-local.yml`:

```yaml
open-daimon:
  ai:
    spring-ai:
      openrouter-auto-rotation:
        models:
          enabled: false
```

## Notes

- `OPENROUTER_KEY` is required even if you only use free models
- If you see 429 errors, the free-tier rate limit was hit — the app retries automatically with a different model
- Monitor usage at [openrouter.ai/activity](https://openrouter.ai/activity)
