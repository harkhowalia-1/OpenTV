import Darwin
import Foundation

/**
 * The TCP socket "TV as Display" streams over, tuned the way game streamers (Moonlight, Parsec) tune theirs:
 *
 * - `TCP_NOTSENT_LOWAT` keeps only a few kilobytes waiting inside the kernel. Everything else waits here, where
 *   the sender can see it (`backlog`) and stop encoding new pictures instead of letting old ones pile up.
 * - Wi-Fi "video" priority (`SO_NET_SERVICE_TYPE`, WMM AC_VI), so the access point sends it first.
 * - Small messages (the cursor) jump ahead of queued video at the next message boundary.
 */
final class StreamSocket: @unchecked Sendable {

    private let fd: Int32
    private let lock = NSCondition()
    private var urgent: [Data] = []
    private var queue: [Data] = []
    private var queuedBytes = 0
    private var closed = false

    /// Called on the writer thread whenever the queue empties, so the sender can encode the latest picture.
    var onDrained: (() -> Void)?
    var onClosed: (() -> Void)?

    /// Bytes handed to `send` but not yet in the kernel.
    var backlog: Int {
        lock.lock(); defer { lock.unlock() }
        return queuedBytes
    }

    init(host: String, port: Int, timeout: TimeInterval = 5) throws {
        var hints = addrinfo(ai_flags: 0, ai_family: AF_UNSPEC, ai_socktype: SOCK_STREAM, ai_protocol: IPPROTO_TCP,
                             ai_addrlen: 0, ai_canonname: nil, ai_addr: nil, ai_next: nil)
        var res: UnsafeMutablePointer<addrinfo>?
        guard getaddrinfo(host, String(port), &hints, &res) == 0, let ai = res else { throw StreamError("Can't reach the TV.") }
        defer { freeaddrinfo(res) }
        fd = socket(ai.pointee.ai_family, SOCK_STREAM, IPPROTO_TCP)
        guard fd >= 0 else { throw StreamError("Can't reach the TV.") }

        var one: Int32 = 1
        setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, socklen_t(MemoryLayout<Int32>.size))
        setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &one, socklen_t(MemoryLayout<Int32>.size))
        var lowat: Int32 = 16 * 1024
        setsockopt(fd, IPPROTO_TCP, TCP_NOTSENT_LOWAT, &lowat, socklen_t(MemoryLayout<Int32>.size))
        var service: Int32 = 3   // NET_SERVICE_TYPE_VI: interactive video
        setsockopt(fd, SOL_SOCKET, 0x1116 /* SO_NET_SERVICE_TYPE */, &service, socklen_t(MemoryLayout<Int32>.size))
        _ = fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) | O_NONBLOCK)

        if Darwin.connect(fd, ai.pointee.ai_addr, ai.pointee.ai_addrlen) != 0 && errno != EINPROGRESS {
            Darwin.close(fd)
            throw StreamError("Can't reach the TV.")
        }
        var p = pollfd(fd: fd, events: Int16(POLLOUT), revents: 0)
        var err: Int32 = 0
        var len = socklen_t(MemoryLayout<Int32>.size)
        guard poll(&p, 1, Int32(timeout * 1000)) == 1, getsockopt(fd, SOL_SOCKET, SO_ERROR, &err, &len) == 0, err == 0 else {
            Darwin.close(fd)
            throw StreamError("Can't reach the TV.")
        }
        let w = Thread { [weak self] in self?.writeLoop() }
        w.name = "tv.stream.write"
        w.qualityOfService = .userInteractive
        w.start()
    }

    /// Queues one message. `urgent` ones go out before any video still waiting.
    func send(_ data: Data, urgent isUrgent: Bool = false) {
        lock.lock()
        if !closed {
            if isUrgent { urgent.append(data) } else { queue.append(data) }
            queuedBytes += data.count
            lock.signal()
        }
        lock.unlock()
    }

    private func writeLoop() {
        while true {
            lock.lock()
            while !closed && urgent.isEmpty && queue.isEmpty { lock.wait() }
            if closed { lock.unlock(); return }
            let next = urgent.isEmpty ? queue.removeFirst() : urgent.removeFirst()
            lock.unlock()
            let ok = writeAll(next)
            lock.lock()
            queuedBytes -= next.count
            let empty = queue.isEmpty && urgent.isEmpty
            lock.unlock()
            if !ok { close(); return }
            if empty { onDrained?() }
        }
    }

    /// Writes in small pieces, each only once the kernel's unsent data is under the low-water mark.
    private func writeAll(_ data: Data) -> Bool {
        data.withUnsafeBytes { raw -> Bool in
            guard let base = raw.baseAddress else { return true }
            var off = 0
            while off < raw.count {
                var p = pollfd(fd: fd, events: Int16(POLLOUT), revents: 0)
                let r = poll(&p, 1, 2000)
                if r < 0 && errno == EINTR { continue }
                guard r == 1, p.revents & Int16(POLLOUT) != 0 else { return false }
                let n = Darwin.send(fd, base + off, min(32 * 1024, raw.count - off), 0)
                if n < 0 {
                    if errno == EAGAIN || errno == EINTR { continue }
                    return false
                }
                off += n
            }
            return true
        }
    }

    /// Blocks (on the caller's thread) until `count` bytes have arrived. Nil when the connection ends.
    func read(_ count: Int) -> Data? {
        var out = Data(count: count)
        var got = 0
        while got < count {
            var p = pollfd(fd: fd, events: Int16(POLLIN), revents: 0)
            let r = poll(&p, 1, 500)
            if r == 0 { if isClosed { return nil } else { continue } }
            if r < 0 { if errno == EINTR { continue } else { return nil } }
            let n = out.withUnsafeMutableBytes { recv(fd, $0.baseAddress! + got, count - got, 0) }
            if n == 0 { return nil }
            if n < 0 { if errno == EAGAIN || errno == EINTR { continue } else { return nil } }
            got += n
        }
        return out
    }

    var isClosed: Bool {
        lock.lock(); defer { lock.unlock() }
        return closed
    }

    func close() {
        lock.lock()
        let was = closed
        closed = true
        urgent.removeAll()
        queue.removeAll()
        queuedBytes = 0
        lock.broadcast()
        lock.unlock()
        guard !was else { return }
        shutdown(fd, SHUT_RDWR)
        Darwin.close(fd)
        onClosed?()
    }
}
