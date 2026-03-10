package org.iotsplab.akiba.utils.io

import kotlinx.io.files.FileNotFoundException
import java.io.RandomAccessFile
import java.nio.file.Path

class ReversedFileLinesIterator(private val path: Path): Iterator<String> {
    private var position: Long = path.toFile().length()
    private val lineBreak = "\n".toByteArray()[0]
    private val buf = ByteArray(1024)
    private var bufPtr = 0
    private var currentLine = StringBuilder()

    override fun next(): String {
        RandomAccessFile(path.toFile(), "r").use { file ->
            while (bufPtr >= 0) {
                if (buf[bufPtr] == lineBreak && currentLine.isNotEmpty()) {
                    val ret = currentLine.reverse().toString()
                    currentLine.clear()
                    --bufPtr
                    return ret
                } else {
                    currentLine.append(buf[bufPtr].toInt().toChar())
                    --bufPtr
                }
            }

            while (position > 0) {
                val bytesToRead = minOf(position, buf.size.toLong()).toInt()
                position -= bytesToRead
                file.seek(position)

                file.readFully(buf, 0, bytesToRead)
                bufPtr = bytesToRead - 1

                while (bufPtr >= 0) {
                    if (buf[bufPtr] == lineBreak && currentLine.isNotEmpty()) {
                        val ret = currentLine.reverse().toString()
                        currentLine.clear()
                        --bufPtr
                        return ret
                    } else {
                        currentLine.append(buf[bufPtr].toInt().toChar())
                        --bufPtr
                    }
                }
            }

            // Reached at the beginning of the file
            if (currentLine.isNotEmpty()) {
                val ret = currentLine.reverse().toString()
                currentLine.clear()
                return ret
            } else
                throw NoSuchElementException("File has iterated to the beginning")
        }
    }

    override fun hasNext(): Boolean {
        return position > 0 || bufPtr > 0
    }

    init {
        if (!path.toFile().exists())
            throw FileNotFoundException("File $path does not exist.")
    }
}