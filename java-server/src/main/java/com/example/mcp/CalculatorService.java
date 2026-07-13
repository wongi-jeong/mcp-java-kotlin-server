package com.example.mcp;

public class CalculatorService {

    public String add(double a, double b) {
        return String.format("%.6g + %.6g = %.6g", a, b, a + b);
    }
}
