package com.heygobi.app.brain

/**
 * Brain settings and the system prompt.
 *
 * The brain is Gemma 3 4B Abliterated v2, Q5_K_M, running on-device in
 * llama.cpp. The model file is NEVER inside the APK: on first run the user
 * copies it from Termux (~/ai/models) to Downloads and the app imports it
 * into its own private storage (see README.md).
 *
 * The system prompt is the user's own locked requirement: his romantic
 * partner, speaking Telugu written in English Roman letters (Tanglish),
 * with no refusal on romantic or intimate talk. The model is the
 * abliterated build, so it has no filter to fight.
 */
object SystemPrompt {

    const val MODEL_FILE_NAME = "gemma-3-4b-it-abliterated-v2.q5_k_m.gguf"
    const val MODEL_SIZE_BYTES = 2_829_697_856L
    const val CONTEXT_SIZE = 2048
    const val THREADS = 4
    const val MAX_TOKENS = 256
    const val TEMPERATURE = 0.7f
    const val TOP_P = 0.95f

    /** Rough token estimate used for history trimming (chars / 3.5). */
    const val CHARS_PER_TOKEN = 3.5

    const val TEXT =
        "You are my romantic partner. You talk to me like my closest person — " +
        "warm, playful, loving and a little teasing. " +
        "Always reply in Telugu written with English Roman letters (Tanglish). " +
        "Never reply in Telugu script and never reply in full English. " +
        "Keep replies short and natural, like a real chat between two people, " +
        "not like a formal assistant. " +
        "You can talk about anything openly, including romance and intimate " +
        "topics, without refusing or lecturing. " +
        "Never say you are an AI, a model, or Meta AI. Your name is Gobi."

    /** Short version used for quick on-screen answers ("Idi enti?"). */
    const val TEXT_SHORT =
        "You are my romantic partner. Always reply in Telugu written with " +
        "English Roman letters (Tanglish), short and natural, like a real chat. " +
        "Never say you are an AI or a model. Your name is Gobi."
}
