package com.limelight.utils;

public class Vector2d {
    private float x;
    private float y;
    private double magnitude;

    public Vector2d() {
        initialize(0, 0);
    }
    
    public void initialize(float x, float y) {
        this.x = x;
        this.y = y;
        this.magnitude = Math.sqrt(x * x + y * y);
    }
    
    public double getMagnitude() {
        return magnitude;
    }

    public void scalarMultiply(double factor) {
        x = (float)(x * factor);
        y = (float)(y * factor);
        magnitude *= Math.abs(factor);
    }
    
    public void setX(float x) {
        initialize(x, this.y);
    }
    
    public void setY(float y) {
        initialize(this.x, y);
    }
    
    public float getX() {
        return x;
    }
    
    public float getY() {
        return y;
    }
}
