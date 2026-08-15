import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { validateTokensOnStartup } from '../src/auth.js';

describe('validateTokensOnStartup', () => {
  let originalPhone;
  let originalPane;

  beforeEach(() => {
    originalPhone = process.env.GLASS_PHONE_TOKEN;
    originalPane = process.env.GLASS_PANE_TOKEN;
  });

  afterEach(() => {
    process.env.GLASS_PHONE_TOKEN = originalPhone;
    process.env.GLASS_PANE_TOKEN = originalPane;
  });

  it('throws when GLASS_PHONE_TOKEN is missing', () => {
    delete process.env.GLASS_PHONE_TOKEN;
    process.env.GLASS_PANE_TOKEN = 'valid';
    
    expect(() => validateTokensOnStartup()).toThrow('GLASS_PHONE_TOKEN must be set');
  });

  it('throws when GLASS_PANE_TOKEN is missing', () => {
    process.env.GLASS_PHONE_TOKEN = 'valid';
    delete process.env.GLASS_PANE_TOKEN;
    
    expect(() => validateTokensOnStartup()).toThrow('GLASS_PANE_TOKEN must be set');
  });

  it('throws when token is empty string', () => {
    process.env.GLASS_PHONE_TOKEN = '';
    process.env.GLASS_PANE_TOKEN = 'valid';
    
    expect(() => validateTokensOnStartup()).toThrow('GLASS_PHONE_TOKEN must be set');
  });

  it('throws when token is whitespace only', () => {
    process.env.GLASS_PHONE_TOKEN = '   ';
    process.env.GLASS_PANE_TOKEN = 'valid';
    
    expect(() => validateTokensOnStartup()).toThrow('GLASS_PHONE_TOKEN must be set');
  });
});
