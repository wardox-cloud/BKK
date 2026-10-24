# Phone benchmark: your phone's transcription vs Murmur

A 10-minute test you run on your own phone. The same recording goes through Samsung's transcription and through Murmur, and `phone_score.py` compares both with what you actually said.

## The script

Read it at your normal pace, the way you'd normally talk. Sentence 1 has a self-correction and sentence 9 has an "um" and an "uh"; say those too, because cleaning them up is part of the test. Pause briefly between sentences.

1. Hey Ali, can you send me the updated report by Friday? Actually, make it Thursday.
2. Yaar mujhe kal client ke saath meeting karni hai, so please remind me at four thirty.
3. The candidate has seven years of experience in mortgage servicing and is based in Lahore.
4. Acha suno, woh proposal abhi tak finalize nahi hua, I'll follow up with Sarah tomorrow.
5. Please book a call with Matt Doyle for next Tuesday at 11 am Eastern.
6. Mujhe lagta hai ke humein pricing pe dobara kaam karna chahiye, the numbers don't add up.
7. Send the invoice to accounts at axiom talent dot com, and copy me on it.
8. Theek hai, main shaam ko call karta hoon, abhi main traffic mein phansa hua hoon.
9. Um, so the main thing is, uh, we need three more candidates before the end of the month.
10. Bhai, ye wala resume bohat strong hai, let's move him to the interview stage.

Half the sentences are plain English with names, numbers and an email address. The other half mix Urdu and English the way people actually talk.

## Before you start

Install Murmur build 9 or newer from [the latest release](https://github.com/wardox-cloud/BKK/releases/latest). In Murmur, set **How you speak** to *English + Urdu, Urdu in Roman letters*, add your Gemini key, and tap **Save**. Keep the style on *Clean*.

## Test 1: one recording, both systems (the fair test)

1. Open **Voice Recorder** and record yourself reading the script.
2. **Samsung:** open the recording and tap **Transcribe**. Pick **English (India)**, the closest option, since Samsung has no Urdu. If it asks, download the language pack. Copy the transcript.
   - Optional: transcribe it again in **Hindi** to see what Samsung does with the Urdu parts.
3. **Murmur:** go back to the list of recordings, share the same recording (long-press, **Share**, choose the audio file), and pick **Transcribe with Murmur**. When it finishes, the text is copied.

## Test 2: live typing (optional)

These listen while you talk, so read the script again for each one, into any notes app:

- **Gboard voice typing:** in Gboard settings, under **Voice typing**, choose *Hinglish* or *English (India)*, then tap Gboard's mic.
- **Live Transcribe** (if your phone has it: Settings → Accessibility → Hearing enhancements). Check whether its language list includes Urdu.
- **Murmur's floating mic.**

## Scoring

Put each transcript under its own heading in one text file (or paste them to Claude in this format):

```
## Samsung
...paste...
## Murmur
...paste...
## Gboard
...paste...
```

Then run `python eval/phone_score.py results.txt --details`.

The score doesn't count punctuation, capital letters, "4:30" vs "four thirty", or common spelling differences in Roman Urdu (*nahi*, *nahin* and *nai* all count). Leaving out the "um", the "uh" or the corrected "Friday" isn't an error either, since that's what a cleanup should do. It reports:

- **Word errors:** the share of the script's words that came out wrong, missing or extra.
- **English words right** and **Urdu words right, in Roman letters**: a translated sentence or one in Urdu or Hindi script loses the Urdu words.
- Whether it got the **names**, **numbers**, and the **email address**, applied your **correction**, and removed **um/uh**.
