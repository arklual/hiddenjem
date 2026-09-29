package dev.horizon.platform.spring.caller;

/**
 * Кто задаёт текущий вопрос.
 *
 * <p>Раньше это был пользователь из проверенного токена. Вход выведен из продукта целиком, и ответ
 * всегда один — {@link Caller#SINGLE}. Класс оставлен точкой, через которую сервисы узнают
 * спрашивающего: контроллерам не нужно знать, откуда он берётся.
 */
public class CurrentCaller {

    public Caller require() {
        return Caller.SINGLE;
    }
}
