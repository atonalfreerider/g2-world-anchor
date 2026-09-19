package com.g2bridge

import kotlin.math.acos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    fun norm() = sqrt(x * x + y * y + z * z)
}

/** Row-major, proper 3x3 rotation matrix. */
data class Mat3(val m: DoubleArray) {
    init { require(m.size == 9) }

    operator fun times(v: Vec3) = Vec3(
        m[0] * v.x + m[1] * v.y + m[2] * v.z,
        m[3] * v.x + m[4] * v.y + m[5] * v.z,
        m[6] * v.x + m[7] * v.y + m[8] * v.z,
    )

    operator fun times(o: Mat3): Mat3 {
        val out = DoubleArray(9)
        for (r in 0..2) for (c in 0..2) {
            out[r * 3 + c] = (0..2).sumOf { k -> m[r * 3 + k] * o.m[k * 3 + c] }
        }
        return Mat3(out)
    }

    fun transpose() = Mat3(doubleArrayOf(
        m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8],
    ))

    companion object {
        val IDENTITY = Mat3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))
        /** Marker axes to cyclopean-eye axes for a front-facing forehead mount. */
        val TAG_TO_EYE = Mat3(doubleArrayOf(-1.0, 0.0, 0.0, 0.0, -1.0, 0.0, 0.0, 0.0, 1.0))
    }
}

/** Rigid transform from a local coordinate frame into the stationary phone-camera frame. */
data class Pose3(
    val position: Vec3,
    val rotation: Mat3,
    val timestampNanos: Long,
    val reprojectionErrorPx: Double = 0.0,
) {
    fun transform(local: Vec3) = position + rotation * local
    fun inverseTransform(world: Vec3) = rotation.transpose() * (world - position)

    fun eyeFromForeheadTag(tagToEyeMeters: Vec3 = Vec3(0.0, 0.0, -0.035)): Pose3 {
        return Pose3(
            position = transform(tagToEyeMeters),
            rotation = rotation * Mat3.TAG_TO_EYE,
            timestampNanos = timestampNanos,
            reprojectionErrorPx = reprojectionErrorPx,
        )
    }
}

data class Quaternion(val w: Double, val x: Double, val y: Double, val z: Double) {
    fun normalized(): Quaternion {
        val n = sqrt(w * w + x * x + y * y + z * z).coerceAtLeast(1e-12)
        return Quaternion(w / n, x / n, y / n, z / n)
    }

    fun dot(o: Quaternion) = w * o.w + x * o.x + y * o.y + z * o.z

    fun slerp(otherRaw: Quaternion, t: Double): Quaternion {
        var other = otherRaw
        var d = dot(other)
        if (d < 0.0) {
            other = Quaternion(-other.w, -other.x, -other.y, -other.z)
            d = -d
        }
        d = d.coerceIn(-1.0, 1.0)
        if (d > 0.9995) {
            return Quaternion(
                w + t * (other.w - w), x + t * (other.x - x),
                y + t * (other.y - y), z + t * (other.z - z),
            ).normalized()
        }
        val theta = acos(d)
        val sinTheta = kotlin.math.sin(theta)
        val a = kotlin.math.sin((1.0 - t) * theta) / sinTheta
        val b = kotlin.math.sin(t * theta) / sinTheta
        return Quaternion(a * w + b * other.w, a * x + b * other.x, a * y + b * other.y, a * z + b * other.z)
    }

    fun toMat3(): Mat3 {
        val q = normalized()
        val xx = q.x * q.x; val yy = q.y * q.y; val zz = q.z * q.z
        val xy = q.x * q.y; val xz = q.x * q.z; val yz = q.y * q.z
        val wx = q.w * q.x; val wy = q.w * q.y; val wz = q.w * q.z
        return Mat3(doubleArrayOf(
            1 - 2 * (yy + zz), 2 * (xy - wz), 2 * (xz + wy),
            2 * (xy + wz), 1 - 2 * (xx + zz), 2 * (yz - wx),
            2 * (xz - wy), 2 * (yz + wx), 1 - 2 * (xx + yy),
        ))
    }

    companion object {
        fun fromMat3(r: Mat3): Quaternion {
            val m = r.m
            val trace = m[0] + m[4] + m[8]
            val q = when {
                trace > 0 -> {
                    val s = sqrt(trace + 1.0) * 2
                    Quaternion(0.25 * s, (m[7] - m[5]) / s, (m[2] - m[6]) / s, (m[3] - m[1]) / s)
                }
                m[0] > m[4] && m[0] > m[8] -> {
                    val s = sqrt(1.0 + m[0] - m[4] - m[8]) * 2
                    Quaternion((m[7] - m[5]) / s, 0.25 * s, (m[1] + m[3]) / s, (m[2] + m[6]) / s)
                }
                m[4] > m[8] -> {
                    val s = sqrt(1.0 + m[4] - m[0] - m[8]) * 2
                    Quaternion((m[2] - m[6]) / s, (m[1] + m[3]) / s, 0.25 * s, (m[5] + m[7]) / s)
                }
                else -> {
                    val s = sqrt(1.0 + m[8] - m[0] - m[4]) * 2
                    Quaternion((m[3] - m[1]) / s, (m[2] + m[6]) / s, (m[5] + m[7]) / s, 0.25 * s)
                }
            }
            return q.normalized()
        }
    }
}

/** Adaptive low-pass filter: stable at rest but responsive during real head motion. */
class PoseFilter(
    private val minimumCutoffHz: Double = 1.4,
    private val speedCoefficient: Double = 0.06,
) {
    private var last: Pose3? = null
    private var lastVelocity = Vec3(0.0, 0.0, 0.0)

    fun reset() { last = null; lastVelocity = Vec3(0.0, 0.0, 0.0) }

    fun update(raw: Pose3): Pose3 {
        val prior = last ?: return raw.also { last = it }
        val dt = ((raw.timestampNanos - prior.timestampNanos) / 1e9).coerceIn(1.0 / 120.0, 0.1)
        val velocity = (raw.position - prior.position) * (1.0 / dt)
        val smoothedSpeed = velocity.norm()
        val cutoff = minimumCutoffHz + speedCoefficient * smoothedSpeed * 100.0
        val alpha = 1.0 - exp(-2.0 * Math.PI * cutoff * dt)
        val pos = prior.position + (raw.position - prior.position) * alpha

        val q0 = Quaternion.fromMat3(prior.rotation)
        val q1 = Quaternion.fromMat3(raw.rotation)
        val angular = 2.0 * acos(min(1.0, max(-1.0, kotlin.math.abs(q0.dot(q1))))) / dt
        val rotationAlpha = (alpha + angular * 0.025).coerceIn(alpha, 0.9)
        val filtered = Pose3(pos, q0.slerp(q1, rotationAlpha).toMat3(), raw.timestampNanos, raw.reprojectionErrorPx)
        lastVelocity = lastVelocity * 0.55 + velocity * 0.45
        last = filtered
        return filtered
    }

    /** Compensates part of camera + BLE/display latency; deliberately capped. */
    fun predict(pose: Pose3, latencyMs: Double): Pose3 {
        val seconds = (latencyMs / 1000.0).coerceIn(0.0, 0.22)
        return pose.copy(position = pose.position + lastVelocity * seconds)
    }
}
