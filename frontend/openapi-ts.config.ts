import { defineConfig } from 'openapi-typescript'

export default defineConfig({
  input: 'http://localhost:8080/openapi.json',
  output: 'src/lib/api-types.ts',
})
