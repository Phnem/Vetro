# Privacy Policy for Vetro

**Last updated: November 15, 2026**

This Privacy Policy describes how **Vetro** (the "Application") handles your information. The Application is a media manager designed to respect user privacy; an account is optional.

### 1. Information Collection and Use
Vetro does not collect, store, or transmit any personal identification information (such as your name, email address, or phone number) to the developer or any third-party servers, except for the synchronization process described below and the optional, off-by-default model-training contribution described in section 5.

### 2. Dropbox Integration
The Application uses the Dropbox API to provide cloud synchronization and backup features.
* **Access Scope:** The Application requests access only to its own dedicated folder (`App Folder`) within your Dropbox account. It cannot see, access, or modify any other files in your Dropbox.
* **Data Storage:** Your data (media lists, settings, and backups) is stored directly in your personal Dropbox account. The developer of Vetro has no access to this data.
* **Authentication:** Authentication is handled entirely by Dropbox through OAuth2. The Application does not see or store your Dropbox password.

### 3. Local Data Storage
All data managed by the Application is stored locally on your device. This data remains on your device unless you choose to use the Dropbox synchronization feature or manually export your data.

### 4. Third-Party Services
Except for Dropbox (which is used only if you enable sync), the Application does not use any third-party analytics, advertising, or tracking services.

### 5. Optional contribution to model training and the book catalogue (off by default)
Vetro's on-device models (title matching, file-name parsing, recommendations) and its book catalogue can be improved with anonymous data from people who choose to help. This is **off until you switch it on** in Settings → Smart models → "Help improve Vetro" and confirm the dialog that explains it, and it exists only in builds signed by the developer (not in F-Droid builds). Switching it off immediately deletes the random identifier and anything waiting to be sent. The three models themselves (title matching, files and genres, taste recommendations) run on your phone and are switched on separately in the same section; they send nothing by themselves.

When on, at most one upload per day goes to a Cloudflare Worker run by the developer, which saves it as plain files in the developer's own storage (Cloudflare R2). You can read the exact text of the next upload in Settings → "What will be sent". An upload has two parts. The first, numbers for the models, can contain only:
* 11 learned weights of the on-device recommendation ranker and how many ratings produced them;
* for pairs of records where your own action settled the question (you picked the right catalog match, or marked a match wrong): 42 similarity numbers and a yes/no label, with no titles and no identifier;
* genre names returned by catalog sources that the app did not recognise;
* counters of what the models did (for example "search order changed: 12"), and the version of each model pack.

The second part is book records: when a search finds a book on another book site that the Vetro catalogue does not have, the public facts about that book (the site's name and the book's address on it, title, authors, narrators, genres, year, cover link, length) are sent so the developer can add the book to the catalogue. This part carries no identifier at all, and never the text you typed to search.

It never contains the titles of your collection, what you typed in a search, file names or paths, notes, comments, your account, your IP address (the server does not store it), or timestamps finer than a day. The only identifier, used for the numbers for the models, is 16 random bytes created when you switch the option on, unrelated to your device or account, and replaced every 30 days. The data is used to retrain and calibrate the models and to extend the catalogue offline; files are deleted after at most 400 days.

### 6. Changes to This Privacy Policy
The developer may update this Privacy Policy from time to time. Any changes will be reflected by updating the "Last updated" date at the top of this page.

### 7. Contact Information
If you have any questions about this Privacy Policy, you can contact the developer via GitHub: https://github.com/Phnem