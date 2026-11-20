// imu_fusion.h - fusao giroscopio+acelerometro (Madgwick, 6 eixos). C++ puro, sem Arduino.
#pragma once
#include <math.h>

struct Madgwick {
    float q0 = 1, q1 = 0, q2 = 0, q3 = 0;
    float beta = 0.06f;

    void reset() { q0 = 1; q1 = q2 = q3 = 0; }

    // gx,gy,gz em rad/s; ax,ay,az em qualquer unidade (so a direcao importa)
    void update(float gx, float gy, float gz, float ax, float ay, float az, float dt) {
        float qDot1 = 0.5f * (-q1 * gx - q2 * gy - q3 * gz);
        float qDot2 = 0.5f * ( q0 * gx + q2 * gz - q3 * gy);
        float qDot3 = 0.5f * ( q0 * gy - q1 * gz + q3 * gx);
        float qDot4 = 0.5f * ( q0 * gz + q1 * gy - q2 * gx);

        float an = ax * ax + ay * ay + az * az;
        if (an > 1e-6f) {
            float r = 1.0f / sqrtf(an);
            ax *= r; ay *= r; az *= r;
            const float _2q0 = 2 * q0, _2q1 = 2 * q1, _2q2 = 2 * q2, _2q3 = 2 * q3;
            const float _4q0 = 4 * q0, _4q1 = 4 * q1, _4q2 = 4 * q2;
            const float _8q1 = 8 * q1, _8q2 = 8 * q2;
            const float q0q0 = q0 * q0, q1q1 = q1 * q1, q2q2 = q2 * q2, q3q3 = q3 * q3;
            float s0 = _4q0 * q2q2 + _2q2 * ax + _4q0 * q1q1 - _2q1 * ay;
            float s1 = _4q1 * q3q3 - _2q3 * ax + 4 * q0q0 * q1 - _2q0 * ay - _4q1 + _8q1 * q1q1 + _8q1 * q2q2 + _4q1 * az;
            float s2 = 4 * q0q0 * q2 + _2q0 * ax + _4q2 * q3q3 - _2q3 * ay - _4q2 + _8q2 * q1q1 + _8q2 * q2q2 + _4q2 * az;
            float s3 = 4 * q1q1 * q3 - _2q1 * ax + 4 * q2q2 * q3 - _2q2 * ay;
            float sn = s0 * s0 + s1 * s1 + s2 * s2 + s3 * s3;
            if (sn > 1e-12f) {
                r = 1.0f / sqrtf(sn);
                qDot1 -= beta * s0 * r; qDot2 -= beta * s1 * r; qDot3 -= beta * s2 * r; qDot4 -= beta * s3 * r;
            }
        }
        q0 += qDot1 * dt; q1 += qDot2 * dt; q2 += qDot3 * dt; q3 += qDot4 * dt;
        float n = 1.0f / sqrtf(q0 * q0 + q1 * q1 + q2 * q2 + q3 * q3);
        q0 *= n; q1 *= n; q2 *= n; q3 *= n;
    }

    // Angulos em graus (sequencia Z-Y-X): roll = giro em X, pitch = giro em Y, yaw = giro em Z
    void euler(float& roll, float& pitch, float& yaw) const {
        const float R2D = 57.29577951308232f;
        roll = atan2f(2 * (q0 * q1 + q2 * q3), 1 - 2 * (q1 * q1 + q2 * q2)) * R2D;
        float sp = 2 * (q0 * q2 - q3 * q1);
        if (sp > 1) sp = 1;
        if (sp < -1) sp = -1;
        pitch = asinf(sp) * R2D;
        yaw = atan2f(2 * (q0 * q3 + q1 * q2), 1 - 2 * (q2 * q2 + q3 * q3)) * R2D;
    }
};
