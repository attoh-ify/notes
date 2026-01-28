package com.example.notes.shared.operation_transformations.impl;

import com.example.notes.dto.ot.OperationNameEnum;
import com.example.notes.dto.ot.TextOperation;
import com.example.notes.shared.operation_transformations.OperationTransformations;

public class SimpleCharacterOperationTransformations implements OperationTransformations {
    @Override
    public TextOperation[] transform(TextOperation op1, TextOperation op2) {
        var op1Name = op1.getOpName();
        var op2Name = op2.getOpName();

        if (op1Name.equals(OperationNameEnum.INS) && op2Name.equals(OperationNameEnum.INS)) {
            return new TextOperation[]{transformII(op1, op2)};
        } else if (op1Name.equals(OperationNameEnum.INS) && op2Name.equals(OperationNameEnum.DEL)) {
            return new TextOperation[]{transformID(op1, op2)};
        } else if (op1Name.equals(OperationNameEnum.DEL) && op2Name.equals(OperationNameEnum.INS)) {
            return new TextOperation[]{transformDI(op1, op2)};
        } else if (op1Name.equals(OperationNameEnum.DEL) && op2Name.equals(OperationNameEnum.DEL)) {
            var transform = transformDD(op1, op2);
            return transform == null ? null : new TextOperation[]{transform};
        } else return null;
    }

    // insert-insert
    private TextOperation transformII(TextOperation op1, TextOperation op2) {
        if (op1.getPosition() < op2.getPosition()) {
            return new TextOperation(op1.getOpName(), op1.getOperand(), op1.getPosition());
        } else {
            return new TextOperation(op1.getOpName(), op1.getOperand(), op1.getPosition() + 1);
        }
    }

    // insert-delete
    private TextOperation transformID(TextOperation op1, TextOperation op2) {
        int newPos;
        if (op1.getPosition() <= op2.getPosition()) newPos = op1.getPosition();
        else newPos = op1.getPosition() - 1;
        return new TextOperation(op1.getOpName(), op1.getOperand(), newPos);
    }

    // delete-insert
    private TextOperation transformDI(TextOperation op1, TextOperation op2) {
        int newPos;
        if (op1.getPosition() < op2.getPosition()) newPos = op1.getPosition();
        else newPos = op1.getPosition() + 1;
        return new TextOperation(op1.getOpName(), op1.getOperand(), newPos);
    }

    // delete-delete
    private TextOperation transformDD(TextOperation op1, TextOperation op2) {
        int newPos;
        if (op1.getPosition() < op2.getPosition()) newPos = op1.getPosition();
        else if (op1.getPosition() > op2.getPosition()) newPos = op1.getPosition() - 1;
        else return null;
        return new TextOperation(op1.getOpName(), op1.getOperand(), newPos);
    }
}
