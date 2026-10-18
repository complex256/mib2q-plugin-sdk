package mibsdk.api;

public interface Hooks {

    /** Blocks the stock behaviour at the hook point while active. Needs {@link Permissions#HOOKS_PARKING}. */
    Veto veto(HookPoint point);

    /** Observes values passing the hook point. Needs {@link Permissions#HOOKS_PARKING}. */
    Subscription observe(HookPoint point, Listener<HookObserved> l);
}
