package kotlinx.io

import kotlinx.io.buffer.*
import kotlinx.io.bytes.*
import kotlinx.io.pool.*

/**
 * [Input] is an abstract base class for synchronous byte readers.
 *
 * It contains [read*] methods to read primitive types ([readByte], [readInt], ...) and arrays([readByteArray]).
 *
 * [Input] is buffered. Buffer size depends on [Buffer.size] in the [bufferPool] buffer.
 * Buffer size is [DEFAULT_BUFFER_SIZE] by default.
 *
 * To ensure that the required amount bytes is available, you can prefetch it with the [prefetch] method.
 *
 * [Input] supports a rewind mechanism with the [preview] method.
 *
 * [Input] is a resource because it holds the source. The user has to close [Input] with the [close] method at the end.
 *
 * To implement [Input] over a custom source you should override [fill] and [closeSource] methods:
 * ```
 * class Input42 : Input() {
 *  private var closed: Boolean = false
 *
 *   override fun fill(buffer: Buffer): Int {
 *      if (closed) {
 *          return 0
 *      }
 *
 *      buffer.storeByteAt(index = 0, value = 42)
 *      return 1
 *   }
 *
 *   override fun closeSource() {
 *      closed = true
 *   }
 * }
 * ```
 *
 * Please note that [Input] doesn't provide any synchronization over source and doesn't support concurrent access.
 *
 * TODO: document [prefetch] and (abstract, protected) API.
 */
public abstract class Input : Closeable {
    /**
     * Pool for obtaining buffers for operations.
     */
    private val bufferPool: ObjectPool<Buffer>

    /**
     * Buffer for current operations.
     * Note, that buffer can be exhausted (position == limit).
     */
    private var buffer: Buffer

    /**
     * Current reading position in the [buffer].
     */
    private var position: Int = 0

    /**
     * Number of bytes loaded into the [buffer].
     */
    private var limit: Int = 0

    /**
     * Index of a current buffer in the [previewBytes].
     */
    private var previewIndex: Int = 0

    /**
     * Flag to indicate if current [previewBytes] are being discarded.
     * When no preview is in progress and a current [buffer] has been exhausted, it should be discarded.
     */
    private var previewDiscard: Boolean = true

    /**
     * Recorded buffers for preview operation.
     */
    private var previewBytes: Bytes? = null

    /**
     * Constructs a new Input with the given `bufferPool`.
     */
    protected constructor(pool: ObjectPool<Buffer>) {
        bufferPool = pool
        buffer = pool.borrow()
        previewBytes = null
    }

    /**
     * Constructs a new Input with the given [bytes], pool is taken from [bytes].
     */
    internal constructor(bytes: Bytes, pool: ObjectPool<Buffer>) {
        bufferPool = pool

        if (bytes.size() == 0) {
            buffer = Buffer.EMPTY
            return
        }

        previewBytes = bytes
        bytes.pointed(0) { firstBuffer, firstLimit ->
            buffer = firstBuffer
            limit = firstLimit
        }
    }

    /**
     * Constructs a new Input with the default pool of buffers with the given [bufferSize].
     */
    protected constructor(bufferSize: Int = DEFAULT_BUFFER_SIZE) : this(
        if (bufferSize == DEFAULT_BUFFER_SIZE)
            DefaultBufferPool.Instance
        else
            DefaultBufferPool(bufferSize)
    )

    /**
     * Reads a [Byte] from this Input.
     *
     * @throws EOFException if no more bytes can be read.
     */
    public fun readByte(): Byte = readPrimitive(
        1, { buffer, offset -> buffer.loadByteAt(offset) },
        { it.toByte() }
    )

    /**
     * Reads a [Short] from this Input.
     *
     * @throws EOFException if no more bytes can be read.
     */
    public fun readShort(): Short = readPrimitive(
        2, { buffer, offset -> buffer.loadShortAt(offset) },
        { it.toShort() }
    )

    /**
     * Reads an [Int] from this Input.
     *
     * @throws EOFException if no more bytes can be read.
     */
    public fun readInt(): Int = readPrimitive(
        4, { buffer, offset -> buffer.loadIntAt(offset) },
        { it.toInt() }
    )

    /**
     * Reads a [Long] from this Input.
     *
     * @throws EOFException if no more bytes can be read.
     */
    public fun readLong(): Long = readPrimitive(
        8, { buffer, offset -> buffer.loadLongAt(offset) },
        { it }
    )

    /**
     * Check if at least 1 byte available to read.
     *
     * The method could block until source provides a byte.
     */
    @Suppress("NOTHING_TO_INLINE")
    public inline fun eof(): Boolean = !prefetch(1)

    /**
     * Check that [size] bytes are fetched in [Input].
     *
     * @return true if this [Input] contains at least [size] bytes.
     */
    public fun prefetch(size: Int): Boolean {
        if (position == limit) {
            if (fetchBuffer() == 0) {
                return false
            }
        }

        var left = size - (limit - position)
        if (left <= 0) {
            return true // Enough bytes in current buffer
        }

        // We will fetch bytes into additional buffers, so prepare preview
        val bytes = previewBytes ?: startPreview()

        var fetchIndex = previewIndex
        while (!bytes.isAfterLast(fetchIndex)) {
            left -= bytes.limit(fetchIndex)
            if (left <= 0) {
                // Enough bytes in preview bytes
                return true
            }

            fetchIndex++
        }

        while (left > 0) {
            val buffer = bufferPool.borrow()
            val limit = fill(buffer)
            if (limit == 0) {
                bufferPool.recycle(buffer)
                return false
            }

            bytes.append(buffer, limit)
            left -= limit
        }
        return true
    }

    /**
     * 1. Output & Input is empty
     * 2. Output is Empty
     */
    public fun readAvailableTo(output: Output) {
        if (position == limit) {
            fillBuffer()
        }

        output.writeBufferDirect(buffer, position, limit)
    }

    /**
     * Allows reading from [Input] in the [reader] block without consuming its content.
     *
     * This operation saves the state of the [Input] before [reader] and accumulates buffers for replay.
     * When the [reader] finishes, it rewinds this [Input] to the state before the [reader] block.
     * Please note that [Input] holds accumulated buffers until they are't consumed outside of the [preview].
     *
     * Preview operations can be nested.
     */
    public fun <R> preview(reader: Input.() -> R): R {
        if (position == limit) {
            if (fetchBuffer() == 0) {
                throw EOFException("End of file while reading buffer")
            }
        }

        val markDiscard = previewDiscard
        val markIndex = previewIndex
        val markPosition = position

        previewDiscard = false

        val result = reader()

        previewDiscard = markDiscard
        position = markPosition

        if (previewIndex == markIndex) {
            // we are at the same buffer, just restore the position & state above
            return result
        }

        val bytes = previewBytes!!
        bytes.pointed(markIndex) { buffer, limit ->
            this.buffer = buffer
            this.limit = limit
        }

        previewIndex = markIndex
        return result
    }

    /**
     * Closes input including the underlying source. All pending bytes will be discarded.
     */
    public final override fun close() {
        closeSource()

        bufferPool.recycle(buffer)

        val preview = previewBytes ?: return
        preview.discardFirst()

        while (!preview.isEmpty()) {
            preview.pointed(preview.head) { buffer, _ ->
                bufferPool.recycle(buffer)
            }
        }

        if (DefaultBufferPool.Instance != bufferPool) {
            bufferPool.close()
        }
    }

    /**
     * Closes the underlying source of data used by this input.
     * This method is invoked once the input is [closed][close].
     */
    protected abstract fun closeSource()

    /**
     * Reads the next bytes into the [buffer]
     * May block until at least one byte is available.
     *
     * TODO: ?? Usually bypass all exceptions from the underlying source.
     *
     * @return number of bytes were copied or `0` if no more input is available.
     */
    protected abstract fun fill(buffer: Buffer): Int

    /**
     * Read next bytes into the [destinations].
     * May block until at least one byte is available.
     *
     * TODO: purpose.
     *
     * @return number of bytes were copied or `0` if no more input is available.
     */
    protected open fun fill(destinations: Array<Buffer>): Int = destinations.sumBy { fill(it) }

    /**
     * Allows direct read from a buffer, operates on offset+size, returns number of bytes consumed.
     * NOTE: Dangerous to use, if non-local return then position will not be updated.
     *
     * @throws EOFException if no more bytes can be read.
     */
    internal inline fun readBufferLength(reader: (Buffer, offset: Int, size: Int) -> Int): Int {
        if (position == limit) {
            if (fetchBuffer() == 0) {
                throw EOFException("End of file while reading buffer")
            }
        }
        val consumed = reader(buffer, position, limit - position)
        position += consumed
        return consumed
    }

    internal fun size(): Int {
        val previewSize = previewBytes?.size(previewIndex) ?: limit
        return previewSize - position
    }

    /**
     * Allows direct read from a buffer, operates on startOffset + endOffset (exclusive), returns new position.
     * NOTE: Dangerous to use, if non-local return then position will not be updated.
     *
     * @throws EOFException if no more bytes can be read.
     */
    internal inline fun readBufferRange(reader: (Buffer, startOffset: Int, endOffset: Int) -> Int) {
        var startOffset = position
        var endOffset = limit
        if (startOffset == endOffset) {
            if (fetchBuffer() == 0) {
                throw EOFException("End of file while reading buffer")
            }
            startOffset = 0
            endOffset = limit
        }
        val newPosition = reader(buffer, startOffset, endOffset)
        position = newPosition
    }

    /**
     * Reads a primitive of [primitiveSize] either directly from a buffer with [readDirect]
     * or byte-by-byte into a Long and using [fromLong] to convert to target type.
     */
    private inline fun <T> readPrimitive(
        primitiveSize: Int,
        readDirect: (buffer: Buffer, offset: Int) -> T,
        fromLong: (Long) -> T
    ): T {
        val currentPosition = position
        val currentLimit = limit
        val targetLimit = currentPosition + primitiveSize

        if (currentLimit >= targetLimit) {
            position = targetLimit
            return readDirect(buffer, currentPosition)
        }

        if (currentLimit == currentPosition) {
            // current buffer exhausted and we also don't have bytes left to be read
            // so we should fetch new buffer of data and may be read entire primitive
            if (fetchBuffer() == 0) {
                throw EOFException("End of file while reading buffer")
            }

            // we know we are at zero position here, but limit & buffer could've changed, so can't use cached value
            if (limit >= primitiveSize) {
                position = primitiveSize
                return readDirect(buffer, 0)
            }
        }

        // Nope, doesn't fit in a buffer, read byte by byte
        var long = 0L
        readBytes(primitiveSize) {
            long = (long shl 8) or it.toLong()
        }

        return fromLong(long)
    }

    /**
     * Reads [size] unsigned bytes from an Input and calls [consumer] on each of them.
     * @throws EOFException if no more bytes available.
     */
    private inline fun readBytes(size: Int, consumer: (unsignedByte: Int) -> Unit) {
        var remaining = size

        var current = position
        var currentLimit = limit
        while (remaining > 0) {
            if (current == currentLimit) {
                if (fetchBuffer() == 0) {
                    throw EOFException("End of file while reading buffer")
                }

                current = position
                currentLimit = limit
            }

            val byte = buffer.loadByteAt(current).toInt() and 0xFF
            consumer(byte)

            current++
            remaining--
        }

        position = current
        limit = currentLimit
    }

    /**
     * Prepares this Input for reading from the next buffer, either by filling it from the underlying source
     * or loading from a [previewBytes] after a [preview] operation or if reading from pre-supplied [Bytes].
     *
     * Current [buffer] should be exhausted at this moment, i.e. [position] should be equal to [limit].
     */
    private fun fetchBuffer(): Int {
        val discard = previewDiscard
        val preview = previewBytes

        if (discard) {
            if (preview == null) {
                return fillBuffer()
            }

            return fetchFromPreviewAndDiscard(preview)
        }

        val bytes = preview ?: startPreview()
        return readThroughPreview(bytes)
    }

    /**
     * Instantiate [previewBytes] and fill it.
     */
    private fun startPreview(): Bytes {
        val bytes = Bytes().apply {
            append(buffer, limit)
        }

        previewBytes = bytes
        return bytes
    }

    /**
     * Fetch buffer from [bytes].
     * The [bytes] shouldn't be empty.
     */
    private fun fetchFromPreviewAndDiscard(bytes: Bytes): Int {
        bufferPool.recycle(buffer)
        bytes.discardFirst()

        if (bytes.isEmpty()) {
            previewBytes = null
            return fillBuffer()
        }

        bytes.pointed(0) { newBuffer, newLimit ->
            position = 0
            buffer = newBuffer
            limit = newLimit
        }

        return limit
    }

    private fun readThroughPreview(bytes: Bytes): Int {
        val nextIndex = previewIndex + 1

        if (bytes.isAfterLast(nextIndex)) {
            val fetched = fillBuffer() // received data can be empty
            bytes.append(buffer, limit) // buffer can be empty
            previewIndex = nextIndex
            return fetched
        }

        // we have a buffer already in history, i.e. replaying history inside another preview
        bytes.pointed(nextIndex) { buffer, limit ->
            this.buffer = buffer
            this.limit = limit
        }

        position = 0
        previewIndex = nextIndex
        return limit
    }

    private fun fillBuffer(): Int {
        val source = bufferPool.borrow()
        val fetched = fill(source)
        limit = fetched
        position = 0
        buffer = source
        return fetched
    }
}
