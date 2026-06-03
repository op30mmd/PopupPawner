package com.example.popupblocker;

import org.junit.Test;
import static org.junit.Assert.*;

public class ModuleTest {
    @Test
    public void testModulePresence() {
        // Basic test to verify the class is loadable
        try {
            Class<?> clazz = Class.forName("com.example.popupblocker.PopupBlockerModule");
            assertNotNull(clazz);
        } catch (ClassNotFoundException e) {
            fail("Module class not found");
        }
    }
}
