package com.catoxide.catoxidesbattlerebuild.core.anim.tape;

/** TimeTape 解析/校验错误（消息含定位上下文） */
public class TapeParseException extends RuntimeException {
    public TapeParseException(String message) {
        super(message);
    }
}
