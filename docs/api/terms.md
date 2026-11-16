# Vetro Book Catalogue API: terms of use

> Status: working draft written from the rules the bot already shows to buyers. The owner has not had it reviewed by a lawyer;
> it is not legal advice. Russian version: [terms.ru.md](terms.ru.md). Technical documentation: [README.md](README.md).

By paying for a plan and receiving a key you agree to the following.

## 1. What you get

Access to the API described in [README.md](README.md) within the limits of your plan, for 30 days from payment. Limits and prices of the
plans are shown by the bot before you pay and may change.
The service is provided "as is", without a guarantee of uptime, speed, accuracy or completeness of the data. It can be changed, paused or
stopped, and the catalogue can be corrected or reduced at any time.

## 2. Your key

- The key is personal to the project you described to the bot. Do not sell it, share it, or publish it in a place where anyone can take it
  (a key for a website must be restricted to your domains; for a server or an app keep it out of public repositories).
- You are responsible for everything done with your key. If it leaked, replace it with `/rotate` or disable it with `/revoke`.
- One project, one key set within the number of keys your plan allows. Using several keys or accounts to get around limits is not allowed.

## 3. What is allowed

- Showing search results, book cards and covers to your users inside your product.
- Caching responses and covers for the time given by the `Cache-Control` headers, and storing the facts you need to show a book you have found.
- Commercial use, if you told the bot that the project is commercial.

## 4. What is not allowed

- Building a full or near-full copy of the catalogue, covers or descriptions (bulk scraping, enumerating all ids, mirroring), or publishing
  such a copy. The offline index files are for use inside your own product on a plan that includes them; they may not be republished.
- Reselling or sub-licensing access, or offering the API under your own name as a competing service.
- Getting around limits, key restrictions, domain checks or the app attestation; sending requests with a forged `Origin`; load that
  looks like an attack or is meant to harm the service.
- Using the service for anything illegal, or in a way that infringes the rights of others.
- Removing or hiding the source information the responses carry (for example the source page of a cover), where your own law or the
  source's licence requires you to show it.

## 5. Limits, warnings and bans

The service enforces limits automatically (answer `429`). The owner may, at his own discretion and with or without a prior warning,
depending on how serious the case is:

1. warn you through the bot;
2. lower or cut your limits, or restrict the key to fewer routes;
3. revoke a key;
4. ban the Telegram account: all its keys are revoked and the bot stops issuing keys and accepting payments for it.

Typical reasons: repeated limit violations, signs of scraping or of a copy being built, key sharing or resale, forged origins, abuse of
the bot, false information in the application, a complaint from a rightsholder about your use. When a key is revoked for a
violation the paid period is not refunded. You will receive a message in the bot where this is possible. To contest a decision, write to the owner (contact on the Vetro Studio site).

## 6. Data and rights of third parties

The catalogue is compiled from public sources and from the owner's own work. Titles, author names and similar facts are facts; covers,
descriptions, ratings and other creative material belong to their authors and rightsholders, whatever the technical flags say.

- Descriptions may be taken from Wikipedia and carry its CC BY-SA licence: if you show them, give credit and keep the same licence for the text.
- Covers marked with flag `f & 2` come from sources whose licence does not allow redistribution; they are served at the owner's responsibility
  and may be hidden or removed at any time without notice. Show them only inside your product and be ready to hide them.
- The anime, film and series data comes from public databases (Shikimori, AniList, Kitsu, TMDB, TVmaze and others) and is subject to their terms. TVmaze images are CC BY-SA 4.0 and need credit; if you show anything derived from TMDB you must credit TMDB as its terms require. The `cover` field of each title names the source and licence of its cover.
- The data is not guaranteed to be right: do not use it where an error could cause harm.
- If you are a rightsholder and want something removed, write to the owner (contact on the Vetro Studio site); the request will be checked and the material removed.

## 7. Payments and refunds

Payments are made in Telegram Stars through Telegram, which processes them; the service does not see your payment details. A refund is
possible only at the owner's decision within 7 days of payment and only if the key was not used abusively. The plaintext key is shown once.

## 8. Personal data

The service does not log IP addresses or user agents. It stores your Telegram identifier and name, the description of your project that you
gave to the bot, your orders, and per-key request counts, to run the service and to enforce these terms. Questions about your data: write to the owner.

## 9. Changes

The owner may change these terms; the date of the last change is the date of the file in the repository. The new terms apply to payments
made after the change.
