package app.murmur.core

import kotlin.test.Test
import kotlin.test.assertEquals

class InsertionTest {
    @Test
    fun `adds a space only when the text would run into a word`() {
        assertEquals(" hello", Insertion.spaced("hello", 'k'))
        assertEquals("hello", Insertion.spaced("hello", ' '))
        assertEquals("hello", Insertion.spaced("hello", '\n'))
        assertEquals("hello", Insertion.spaced("hello", null))
    }

    @Test
    fun `inserts at the cursor and replaces a selection`() {
        assertEquals(Insertion.Result("Hi yaar, kal milte hain", 23), Insertion.splice("Hi", 2, 2, "yaar, kal milte hain"))
        assertEquals(Insertion.Result("Dear Ali, thanks", 16), Insertion.splice("Dear Ali, XXX", 10, 13, "thanks"))
        assertEquals(Insertion.Result("note", 4), Insertion.splice("", -1, -1, "note"))
        assertEquals(Insertion.Result("abc def", 7), Insertion.splice("abc", 99, 99, "def"))
    }
}
