package com.g2bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseMathTest {
    @Test fun inverseTransformRoundTrips() {
        val r = Quaternion(0.9238795, 0.0, 0.3826834, 0.0).toMat3()
        val pose = Pose3(Vec3(0.2, -0.1, 0.9), r, 1L)
        val local = Vec3(0.12, 0.04, 0.7)
        val recovered = pose.inverseTransform(pose.transform(local))
        assertEquals(local.x, recovered.x, 1e-6)
        assertEquals(local.y, recovered.y, 1e-6)
        assertEquals(local.z, recovered.z, 1e-6)
    }

    @Test fun neutralFaceLooksTowardPhone() {
        val rotation = FacePoseEstimator.headToCameraRotation(0.0, 0.0, 0.0)
        val forwardWorld = rotation * Vec3(0.0, 0.0, 1.0)
        assertEquals(-1.0, forwardWorld.z, 1e-9)
        assertEquals(0.0, forwardWorld.x, 1e-9)
        assertEquals(0.0, forwardWorld.y, 1e-9)
    }

    @Test fun positiveFaceYawTurnsGazeTowardImageRight() {
        val rotation = FacePoseEstimator.headToCameraRotation(0.0, 20.0, 0.0)
        val forwardWorld = rotation * Vec3(0.0, 0.0, 1.0)
        assertTrue(forwardWorld.x > 0.0)
        assertTrue(forwardWorld.z < 0.0)
    }

    @Test fun eyeLandmarksProduceMetricDepthEstimate() {
        val estimate = FacePoseEstimator.estimate(
            FacePoseSample(360.0, 640.0, 40.0, 180.0, 0.0, 0.0, 0.0, 720, 1280, 1L),
        )!!
        assertEquals("eye landmarks", estimate.scaleSource)
        assertTrue(estimate.pose.position.z in 0.8..1.0)
    }

    @Test fun quaternionMatrixRoundTripKeepsDirection() {
        val q = Quaternion(0.81, -0.19, 0.31, 0.46).normalized()
        val q2 = Quaternion.fromMat3(q.toMat3())
        assertTrue(kotlin.math.abs(q.dot(q2)) > 0.999999)
    }

    @Test fun poseFilterPredictionIsBounded() {
        val filter = PoseFilter()
        filter.update(Pose3(Vec3(0.0, 0.0, 1.0), Mat3.IDENTITY, 0L))
        val moving = filter.update(Pose3(Vec3(0.01, 0.0, 1.0), Mat3.IDENTITY, 20_000_000L))
        val predicted = filter.predict(moving, 1_000.0) // internally capped at 220 ms
        assertTrue(predicted.position.x > moving.position.x)
        assertTrue(predicted.position.x < 0.2)
    }

    @Test fun poseFilterHandlesVeryFastTranslationWithoutInvalidRotationClamp() {
        val filter = PoseFilter()
        filter.update(Pose3(Vec3(0.0, 0.0, 1.0), Mat3.IDENTITY, 0L))
        val fast = filter.update(
            Pose3(
                Vec3(0.75, -0.4, 0.35),
                FacePoseEstimator.headToCameraRotation(25.0, 35.0, 10.0),
                8_333_333L,
            ),
        )
        assertTrue(fast.position.x.isFinite())
        assertTrue(fast.rotation.m.all { it.isFinite() })
    }
}
